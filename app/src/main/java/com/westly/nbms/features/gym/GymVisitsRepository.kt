package com.westly.nbms.features.gym

import android.util.Log
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

private const val TAG = "GymVisits"

internal const val MSG_CHECKIN_EXPIRED = "This membership has expired. Renew before checking in."
internal const val MSG_CHECKIN_SUSPENDED = "This membership is suspended."
internal const val MSG_CHECKIN_CANCELLED = "This membership is cancelled."
internal const val MSG_CHECKIN_TIMEOUT = "The check-in took too long to save. Check your connection and try again."
internal const val MSG_CHECKOUT_TIMEOUT = "The check-out took too long to save. Check your connection and try again."
internal const val MSG_VISIT_NOT_FOUND = "Visit not found."

internal fun alreadyCheckedInMessage(name: String): String = "$name is already checked in."
internal fun alreadyCheckedOutMessage(name: String): String = "$name is already checked out."

// ── what the transaction reads and writes ──

/** The member as the check-in / check-out transaction found it. */
data class LiveGymMember(
    val name: String,
    val status: String,
    val endDate: Instant?,
    val activeVisitId: String?,
    val visitCount: Int,
    val deleted: Boolean
)

/** The visit as the check-out transaction found it. */
data class LiveGymVisit(val memberName: String, val checkedOut: Boolean)

/** What a transaction can do. All reads come first, then the writes (a Firestore rule). */
interface GymTx {
    /** The member document, or null when it does not exist (a removed member is still returned, with `deleted` true). */
    fun readMember(memberId: String): LiveGymMember?

    /** The visit document, or null when it does not exist. */
    fun readVisit(visitId: String): LiveGymVisit?

    fun createVisit(visitId: String, payload: Map<String, Any?>)
    fun updateVisit(visitId: String, fields: Map<String, Any?>)
    fun updateMember(memberId: String, fields: Map<String, Any?>)
}

/** The member's name and the new visit's id after a successful check-in. */
data class CheckInOutcome(val visitId: String, val memberName: String)

data class CheckOutOutcome(val memberName: String)

// ── the pure transaction bodies ──

/** The new `gym_attendance/{id}` document, exactly as Westly wrote it. */
internal fun buildVisitPayload(
    memberId: String,
    memberName: String,
    staffId: String,
    staffName: String,
    dateKey: String
): Map<String, Any?> = mapOf(
    "memberId" to memberId,
    "memberName" to memberName,
    "checkInAt" to GymServerTime,
    "checkOutAt" to null,
    "checkedInBy" to staffId,
    "checkedInByName" to staffName,
    "checkedOutBy" to null,
    "checkedOutByName" to null,
    "dateKey" to dateKey,
    "isDeleted" to false
)

/**
 * Reads the live member first and refuses (with the exact sentence, writing nothing) when the member is missing, not
 * active, or already in the gym. `activeVisitId` is the atomic guard that stops two staff, or a double tap, from creating
 * two open visits. Otherwise creates the visit and updates the member in the same transaction.
 */
internal fun performCheckIn(
    tx: GymTx,
    memberId: String,
    visitId: String,
    staffId: String,
    staffName: String,
    dateKey: String,
    now: Instant
): CheckInOutcome {
    val member = tx.readMember(memberId)
    if (member == null || member.deleted) throw GymException(MSG_MEMBER_NOT_FOUND)
    when (GymLogic.effectiveStatus(member.status, member.endDate, now)) {
        MembershipStatus.ACTIVE -> Unit
        MembershipStatus.EXPIRED -> throw GymException(MSG_CHECKIN_EXPIRED)
        MembershipStatus.SUSPENDED -> throw GymException(MSG_CHECKIN_SUSPENDED)
        MembershipStatus.CANCELLED -> throw GymException(MSG_CHECKIN_CANCELLED)
    }
    if (!member.activeVisitId.isNullOrBlank()) throw GymException(alreadyCheckedInMessage(member.name))

    tx.createVisit(visitId, buildVisitPayload(memberId, member.name, staffId, staffName, dateKey))
    tx.updateMember(
        memberId,
        mapOf(
            "activeVisitId" to visitId,
            "visitCount" to member.visitCount + 1,
            "lastVisitAt" to GymServerTime,
            "updatedAt" to GymServerTime
        )
    )
    return CheckInOutcome(visitId, member.name)
}

/**
 * Closes the visit (server time, who did it) and clears the member's `activeVisitId` only if it still equals this visit's id.
 * A visit that is missing, or already closed (by someone else a moment ago), stops with a clear sentence and writes nothing.
 */
internal fun performCheckOut(
    tx: GymTx,
    visitId: String,
    memberId: String,
    staffId: String,
    staffName: String
): CheckOutOutcome {
    val member = tx.readMember(memberId)
    val visit = tx.readVisit(visitId) ?: throw GymException(MSG_VISIT_NOT_FOUND)
    val name = visit.memberName.ifBlank { member?.name.orEmpty() }
    if (visit.checkedOut) throw GymException(alreadyCheckedOutMessage(name))

    tx.updateVisit(
        visitId,
        mapOf(
            "checkOutAt" to GymServerTime,
            "checkedOutBy" to staffId,
            "checkedOutByName" to staffName
        )
    )
    if (member != null && member.activeVisitId == visitId) {
        tx.updateMember(memberId, mapOf("activeVisitId" to null, "updatedAt" to GymServerTime))
    }
    return CheckOutOutcome(name)
}

// ── the store ──

/** The database calls the visits side of Gym needs. [FirestoreGymVisitsStore] is the real one; the unit tests use a fake. */
interface GymVisitsStore {
    /** Every `gym_attendance` document, live (deleted ones included; the repository leaves them out). */
    fun observeVisits(): Flow<Resource<List<GymVisit>>>

    /** A fresh id for a visit that is about to be created. */
    fun newVisitId(): String

    /** Runs [block] as ONE transaction; everything it writes is saved together or not at all. */
    suspend fun <R> inTransaction(block: (GymTx) -> R): R
}

@Singleton
class FirestoreGymVisitsStore @Inject constructor(
    private val firestore: BusinessFirestore
) : GymVisitsStore {

    override fun observeVisits(): Flow<Resource<List<GymVisit>>> =
        firestore.observeList(GYM_ATTENDANCE, GymVisit::class.java) { it.orderBy("checkInAt", com.google.firebase.firestore.Query.Direction.DESCENDING) }

    override fun newVisitId(): String = firestore.collection(GYM_ATTENDANCE).document().id

    override suspend fun <R> inTransaction(block: (GymTx) -> R): R {
        try {
            return firestore.runTransaction { tx, fs ->
                val real = object : GymTx {
                    override fun readMember(memberId: String): LiveGymMember? {
                        val snap = tx.get(fs.doc(GYM_MEMBERS, memberId))
                        if (!snap.exists()) return null
                        return LiveGymMember(
                            name = snap.getString("name").orEmpty(),
                            status = snap.getString("status").orEmpty(),
                            endDate = snap.getTimestamp("endDate").toGymInstant(),
                            activeVisitId = snap.getString("activeVisitId"),
                            visitCount = snap.getLong("visitCount")?.toInt() ?: 0,
                            deleted = snap.getBoolean("isDeleted") == true
                        )
                    }

                    override fun readVisit(visitId: String): LiveGymVisit? {
                        val snap = tx.get(fs.doc(GYM_ATTENDANCE, visitId))
                        if (!snap.exists()) return null
                        return LiveGymVisit(
                            memberName = snap.getString("memberName").orEmpty(),
                            checkedOut = snap.getTimestamp("checkOutAt") != null
                        )
                    }

                    override fun createVisit(visitId: String, payload: Map<String, Any?>) {
                        tx.set(fs.doc(GYM_ATTENDANCE, visitId), payload.resolveGymServerTime())
                    }

                    override fun updateVisit(visitId: String, fields: Map<String, Any?>) {
                        tx.update(fs.doc(GYM_ATTENDANCE, visitId), fields.resolveGymServerTime())
                    }

                    override fun updateMember(memberId: String, fields: Map<String, Any?>) {
                        tx.update(fs.doc(GYM_MEMBERS, memberId), fields.resolveGymServerTime())
                    }
                }
                block(real)
            }
        } catch (e: Exception) {
            throw e.gymCause() ?: e
        }
    }
}

// ── the repository ──

/** Reads the visit log live and checks members in and out. Check-in and check-out are single transactions with a 20 s limit. */
@Singleton
class GymVisitsRepository @Inject constructor(
    private val store: GymVisitsStore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val notifier: Notifier
) {

    /** Every visit that is not deleted, newest check-in first (the database orders them). */
    fun observeVisits(): Flow<Resource<List<GymVisit>>> =
        store.observeVisits().map { resource ->
            if (resource is Resource.Success) Resource.Success(resource.data.filter { !it.isDeleted }) else resource
        }

    private fun signedIn(): SessionState.SignedIn =
        session.state.value as? SessionState.SignedIn ?: throw GymException(MSG_NOT_SIGNED_IN)

    /**
     * Checks [member] in with ONE transaction (20 s limit) that re-reads the live member. Throws [GymException] with the
     * exact sentence when the member is missing, expired, suspended, cancelled or already in the gym; nothing is written then.
     * The audit entry and the staff alert come after the commit and never turn it into an error.
     */
    suspend fun checkIn(member: GymMember, now: Instant = Instant.now()): CheckInOutcome {
        val s = signedIn()
        val dateKey = GymLogic.dateKey(now, s.business.timezone)
        val visitId = store.newVisitId()
        val committed: CheckInOutcome? = try {
            withTimeoutOrNull(GYM_TRANSACTION_TIMEOUT_MS) {
                store.inTransaction { tx -> performCheckIn(tx, member.id, visitId, s.user.uid, s.user.name, dateKey, now) }
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
        if (committed == null) throw GymException(MSG_CHECKIN_TIMEOUT)

        guarded("audit") { audit.log("gym_checked_in", GYM_ATTENDANCE, committed.visitId, null, mapOf("memberId" to member.id)) }
        guarded("alert") { notifier.notifyGymCheckIn(committed.memberName, s.user.name) }
        return committed
    }

    /** Checks the person of [visit] out with ONE transaction (20 s limit). */
    suspend fun checkOut(visit: GymVisit): CheckOutOutcome {
        val s = signedIn()
        val committed: CheckOutOutcome? = try {
            withTimeoutOrNull(GYM_TRANSACTION_TIMEOUT_MS) {
                store.inTransaction { tx -> performCheckOut(tx, visit.id, visit.memberId, s.user.uid, s.user.name) }
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
        if (committed == null) throw GymException(MSG_CHECKOUT_TIMEOUT)

        guarded("audit") { audit.log("gym_checked_out", GYM_ATTENDANCE, visit.id, null, mapOf("memberId" to visit.memberId)) }
        guarded("alert") { notifier.notifyGymCheckOut(committed.memberName, s.user.name) }
        return committed
    }

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
