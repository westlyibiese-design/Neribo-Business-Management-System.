package com.westly.nbms.features.gym

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GymMembers"

internal const val GYM_MEMBERS = "gym_members"
internal const val GYM_ATTENDANCE = "gym_attendance"
internal const val GYM_CMS_COLLECTION = "cms_content"
internal const val GYM_CMS_DOC_ID = "gym"

internal const val GYM_TRANSACTION_TIMEOUT_MS = 20_000L
internal const val GYM_POST_STEP_TIMEOUT_MS = 5_000L

internal const val MSG_NOT_SIGNED_IN = "Not signed in"
internal const val MSG_MEMBER_NOT_FOUND = "Member not found."
internal const val MSG_NAME_REQUIRED = "Member name is required."
internal const val MSG_DURATION_INVALID = "Enter a whole number of days (1 or more)."
internal const val MSG_PRICE_INVALID = "Enter an amount of 0 or more."
internal const val MSG_PACKAGE_MISSING = "That package is no longer available. Pick another one."
internal const val MSG_SELECT_PACKAGE = "Select a package"
internal const val MSG_MEMBERS_LOAD_FAILED = "We couldn't load gym members."
internal const val MSG_GYM_GENERIC = "Something went wrong. Please try again."
internal const val MSG_RENEW_TIMEOUT = "The renewal took too long to save. Check your connection and try again."

/** Stands for the server time inside a payload; the real stores swap it for `FieldValue.serverTimestamp()`. */
internal object GymServerTime

internal fun Map<String, Any?>.resolveGymServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === GymServerTime) FieldValue.serverTimestamp() else v }

/** Firestore may wrap what a transaction function threw; this digs our own [GymException] back out. */
internal fun Throwable.gymCause(): GymException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is GymException) return current
        current = current.cause
        depth++
    }
    return null
}

// ── small typed-text helpers (private copies, so this phase depends on no other feature's code) ──

/** The typed text as a whole number of 0 or more; blank, decimals, letters and numbers too big for an Int are null. */
internal fun gymParseWhole(text: String): Int? {
    val clean = text.trim()
    if (clean.isEmpty() || !clean.all { it in '0'..'9' }) return null
    return clean.toIntOrNull()
}

/** Keeps only digits (at most 6) while the person types a number of days. */
internal fun gymFilterWholeInput(text: String): String = text.filter { it in '0'..'9' }.take(6)

/** The typed price: blank means 0, otherwise a number of 0 or more. Anything else is null. */
internal fun gymParseMoney(text: String): Double? {
    val clean = text.trim()
    if (clean.isEmpty()) return 0.0
    val value = clean.toDoubleOrNull() ?: return null
    return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
}

/** Keeps only digits and the first dot while the person types a price. */
internal fun gymFilterMoneyInput(text: String): String {
    var seenDot = false
    val out = StringBuilder()
    for (c in text) {
        if (c in '0'..'9') out.append(c)
        else if (c == '.' && !seenDot) {
            seenDot = true
            out.append(c)
        }
    }
    return out.toString()
}

private fun String.nullIfBlank(): String? = trim().ifEmpty { null }

// ── forms and payloads (pure, unit-tested without Android) ──

/** What the person typed in the Register sheet. [packageId] null means "Custom…". */
data class RegisterMemberForm(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val roomNumber: String = "",
    val packageId: String? = null,
    val customName: String = "",
    val durationText: String = "30",
    val priceText: String = "0",
    val notes: String = ""
)

internal data class RegisterErrors(val name: String? = null, val duration: String? = null, val price: String? = null) {
    val any: Boolean get() = name != null || duration != null || price != null
    val first: String? get() = name ?: duration ?: price
}

/** The duration and price are only checked for a custom package (a listed package brings its own). */
internal fun validateRegisterForm(form: RegisterMemberForm): RegisterErrors {
    val custom = form.packageId == null
    return RegisterErrors(
        name = if (form.name.isBlank()) MSG_NAME_REQUIRED else null,
        duration = if (custom && (gymParseWhole(form.durationText)?.let { it >= 1 } != true)) MSG_DURATION_INVALID else null,
        price = if (custom && gymParseMoney(form.priceText) == null) MSG_PRICE_INVALID else null
    )
}

/**
 * The new `gym_members/{id}` document, exactly as Westly wrote it. A listed package gives its name, price and
 * `durationToDays(duration)`; a custom one uses the typed name ("Custom Membership" when blank), price and days.
 * `startDate` is [now], `endDate` is [now] plus the days.
 */
internal fun buildRegisterPayload(
    form: RegisterMemberForm,
    pkg: GymPackage?,
    now: Instant,
    staffId: String,
    staffName: String
): Map<String, Any?> {
    val days = if (pkg != null) GymLogic.durationToDays(pkg.duration) else (gymParseWhole(form.durationText) ?: 30).coerceAtLeast(1)
    val price = pkg?.price ?: (gymParseMoney(form.priceText) ?: 0.0)
    val end = GymLogic.renewalEnd(null, now, days)
    return mapOf(
        "name" to form.name.trim(),
        "email" to form.email.nullIfBlank(),
        "phone" to form.phone.nullIfBlank(),
        "roomNumber" to form.roomNumber.nullIfBlank(),
        "packageId" to (pkg?.id ?: "custom"),
        "packageName" to (pkg?.name ?: form.customName.trim().ifEmpty { "Custom Membership" }),
        "packagePrice" to price,
        "durationDays" to days,
        "startDate" to now.toGymTimestamp(),
        "endDate" to end.toGymTimestamp(),
        "status" to MembershipStatus.ACTIVE.key,
        "statusReason" to null,
        "notes" to form.notes.nullIfBlank(),
        "visitCount" to 0,
        "lastVisitAt" to null,
        "activeVisitId" to null,
        "registeredBy" to staffId,
        "registeredByName" to staffName,
        "createdAt" to GymServerTime,
        "updatedAt" to GymServerTime,
        "isDeleted" to false
    )
}

/** What the person typed in the Edit sheet. */
data class EditMemberForm(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val roomNumber: String = "",
    val notes: String = ""
)

internal fun editFormOf(member: GymMember): EditMemberForm = EditMemberForm(
    name = member.name,
    phone = member.phone.orEmpty(),
    email = member.email.orEmpty(),
    roomNumber = member.roomNumber.orEmpty(),
    notes = member.notes.orEmpty()
)

internal fun validateEditForm(form: EditMemberForm): String? = if (form.name.isBlank()) MSG_NAME_REQUIRED else null

/** The fields an edit writes (the times are server time). */
internal fun buildEditFields(form: EditMemberForm): Map<String, Any?> = mapOf(
    "name" to form.name.trim(),
    "phone" to form.phone.nullIfBlank(),
    "email" to form.email.nullIfBlank(),
    "roomNumber" to form.roomNumber.nullIfBlank(),
    "notes" to form.notes.nullIfBlank(),
    "updatedAt" to GymServerTime
)

/** Suspend / Reactivate: `{status, statusReason null, updatedAt}`. */
internal fun buildStatusFields(status: MembershipStatus): Map<String, Any?> = mapOf(
    "status" to status.key,
    "statusReason" to null,
    "updatedAt" to GymServerTime
)

/** Remove: the member is hidden, never deleted (their visits stay for reporting). */
internal fun buildSoftDeleteFields(): Map<String, Any?> = mapOf("isDeleted" to true, "updatedAt" to GymServerTime)

/** What a renewal writes: the chosen package, the new end date, status active. */
internal fun buildRenewFields(pkg: GymPackage, days: Int, newEnd: Instant): Map<String, Any?> = mapOf(
    "packageId" to pkg.id,
    "packageName" to pkg.name,
    "packagePrice" to pkg.price,
    "durationDays" to days,
    "endDate" to newEnd.toGymTimestamp(),
    "status" to MembershipStatus.ACTIVE.key,
    "updatedAt" to GymServerTime
)

// ── the store ──

/** The member's membership as the renewal transaction found it. */
data class LiveMembership(val name: String, val endDate: Instant?)

/** What a renewal will write, worked out from the live membership. */
class RenewWrite(val fields: Map<String, Any?>, val newEnd: Instant)

class RenewResult(val live: LiveMembership, val write: RenewWrite)

/** The database calls the members side of Gym needs. [FirestoreGymMembersStore] is the real one; the unit tests use a fake. */
interface GymMembersStore {
    /** Every `gym_members` document, live (removed ones included; the repository leaves them out). */
    fun observeMembers(): Flow<Resource<List<GymMember>>>

    /** The packages in `cms_content/gym`. A missing document or field is `Success(emptyList())`; a read failure is `Error`. */
    fun observePackages(): Flow<Resource<List<GymPackage>>>

    /** Adds `gym_members/{new}` and returns the new id. The payload may hold [GymServerTime]. */
    suspend fun add(payload: Map<String, Any?>): String

    /** Updates the given fields of `gym_members/{id}`. */
    suspend fun update(memberId: String, fields: Map<String, Any?>)

    /**
     * ONE transaction: re-read the live member (missing or removed → [GymException] "Member not found."), let [plan] work out
     * what to write from the LIVE end date, write it.
     */
    suspend fun renew(memberId: String, plan: (LiveMembership) -> RenewWrite): RenewResult
}

@Singleton
class FirestoreGymMembersStore @Inject constructor(
    private val firestore: BusinessFirestore
) : GymMembersStore {

    override fun observeMembers(): Flow<Resource<List<GymMember>>> =
        firestore.observeList(GYM_MEMBERS, GymMember::class.java) { it.orderBy("name") }

    override fun observePackages(): Flow<Resource<List<GymPackage>>> =
        firestore.observeDoc(GYM_CMS_COLLECTION, GYM_CMS_DOC_ID, RawGymDocument::class.java).map { resource ->
            when (resource) {
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> resource
                is Resource.Success -> Resource.Success(parseGymPackages(resource.data?.data))
            }
        }

    override suspend fun add(payload: Map<String, Any?>): String = firestore.add(GYM_MEMBERS, payload.resolveGymServerTime())

    override suspend fun update(memberId: String, fields: Map<String, Any?>) {
        firestore.update(GYM_MEMBERS, memberId, fields.resolveGymServerTime())
    }

    override suspend fun renew(memberId: String, plan: (LiveMembership) -> RenewWrite): RenewResult {
        try {
            return firestore.runTransaction { tx, fs ->
                val ref = fs.doc(GYM_MEMBERS, memberId)
                val snap = tx.get(ref)
                if (!snap.exists() || snap.getBoolean("isDeleted") == true) throw GymException(MSG_MEMBER_NOT_FOUND)
                val live = LiveMembership(snap.getString("name").orEmpty(), snap.getTimestamp("endDate").toGymInstant())
                val write = plan(live)
                tx.update(ref, write.fields.resolveGymServerTime())
                RenewResult(live, write)
            }
        } catch (e: Exception) {
            throw e.gymCause() ?: e
        }
    }
}

// ── the repository ──

/** What a renewal did: the member's live name, the package and the old and new end dates. */
data class RenewalOutcome(val name: String, val packageName: String, val oldEnd: Instant?, val newEnd: Instant)

/**
 * Reads gym members and packages live, registers, renews, edits, suspends / reactivates and removes members.
 * Every change is written to the audit log; the staff alerts are best effort.
 */
@Singleton
class GymMembersRepository @Inject constructor(
    private val store: GymMembersStore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val notifier: Notifier
) {

    /** Every member that is not removed, in the order the database returns them (by name). */
    fun observe(): Flow<Resource<List<GymMember>>> =
        store.observeMembers().map { resource ->
            if (resource is Resource.Success) Resource.Success(resource.data.filter { !it.isDeleted }) else resource
        }

    /** The membership packages from `cms_content/gym` (possibly none). */
    fun observePackages(): Flow<Resource<List<GymPackage>>> = store.observePackages()

    private fun signedIn(): SessionState.SignedIn =
        session.state.value as? SessionState.SignedIn ?: throw GymException(MSG_NOT_SIGNED_IN)

    /** Registers a member and returns the new id. A form that fails the checks throws [GymException] and writes nothing. */
    suspend fun register(form: RegisterMemberForm, packages: List<GymPackage>, now: Instant = Instant.now()): String {
        val errors = validateRegisterForm(form)
        if (errors.any) throw GymException(errors.first ?: MSG_NAME_REQUIRED)
        val pkg = form.packageId?.let { id -> packages.firstOrNull { it.id == id } ?: throw GymException(MSG_PACKAGE_MISSING) }
        val s = signedIn()
        val payload = buildRegisterPayload(form, pkg, now, s.user.uid, s.user.name)
        val id = store.add(payload)
        val name = payload["name"] as String
        val packageName = payload["packageName"] as String
        guarded("audit") {
            audit.log("gym_member_registered", GYM_MEMBERS, id, null, mapOf("name" to name, "packageId" to payload["packageId"]))
        }
        guarded("alert") { notifier.notifyGymMembershipRegistered(name, packageName, s.user.name) }
        return id
    }

    /**
     * Renews the member with [pkg] in ONE transaction that re-reads the live end date, so an early renewal keeps the days
     * that are left. Missing member → [GymException] "Member not found.".
     */
    suspend fun renew(memberId: String, pkg: GymPackage, now: Instant = Instant.now()): RenewalOutcome {
        val s = signedIn()
        val days = GymLogic.durationToDays(pkg.duration)
        val committed: RenewResult? = try {
            withTimeoutOrNull(GYM_TRANSACTION_TIMEOUT_MS) {
                store.renew(memberId) { live ->
                    val newEnd = GymLogic.renewalEnd(live.endDate, now, days)
                    RenewWrite(buildRenewFields(pkg, days, newEnd), newEnd)
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
        if (committed == null) throw GymException(MSG_RENEW_TIMEOUT)
        val result: RenewResult = committed
        val name = result.live.name
        guarded("audit") {
            audit.log(
                "gym_membership_renewed", GYM_MEMBERS, memberId,
                mapOf("endDate" to result.live.endDate?.toString()),
                mapOf("endDate" to result.write.newEnd.toString(), "packageId" to pkg.id)
            )
        }
        guarded("alert") { notifier.notifyGymMembershipRenewed(name, pkg.name, s.user.name) }
        return RenewalOutcome(name, pkg.name, result.live.endDate, result.write.newEnd)
    }

    /** Saves the edited details. A blank name throws [GymException] and writes nothing. */
    suspend fun updateDetails(memberId: String, form: EditMemberForm) {
        validateEditForm(form)?.let { throw GymException(it) }
        signedIn()
        store.update(memberId, buildEditFields(form))
        guarded("audit") {
            audit.log(
                "gym_member_updated", GYM_MEMBERS, memberId, null,
                mapOf("name" to form.name.trim(), "phone" to form.phone.nullIfBlank(), "email" to form.email.nullIfBlank(), "roomNumber" to form.roomNumber.nullIfBlank())
            )
        }
    }

    /** Suspends or reactivates the member. Only [MembershipStatus.SUSPENDED] sends the "suspended" alert. */
    suspend fun setStatus(member: GymMember, status: MembershipStatus) {
        val s = signedIn()
        store.update(member.id, buildStatusFields(status))
        guarded("audit") {
            audit.log(
                "gym_membership_status_changed", GYM_MEMBERS, member.id,
                mapOf("status" to member.status), mapOf("status" to status.key)
            )
        }
        if (status == MembershipStatus.SUSPENDED) {
            guarded("alert") { notifier.notifyGymMembershipSuspended(member.name, s.user.name, null) }
        }
    }

    /** Removes the member from the list (`isDeleted` true). Their attendance history is kept. */
    suspend fun softDelete(member: GymMember) {
        signedIn()
        store.update(member.id, buildSoftDeleteFields())
        guarded("audit") { audit.log("gym_member_deleted", GYM_MEMBERS, member.id, mapOf("name" to member.name), null) }
    }

    /** Audit entries and alerts are extras: a failure there never turns a saved change into an error. */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(GYM_POST_STEP_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gym $what step failed", e)
        }
    }
}
