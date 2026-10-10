package com.westly.nbms.features.gym

import com.google.firebase.Timestamp
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

internal class FakeGymAudit : AuditLogger {
    val entries = mutableListOf<List<Any?>>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += listOf(action, collection, documentId, newValue)
    }
}

internal class FakeGymNotifier : Notifier {
    val types = mutableListOf<String>()
    val messages = mutableListOf<String>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        types += type
        messages += message
    }
}

internal class FakeGymSession(timezone: String = "Africa/Lagos") : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        SessionState.SignedIn(
            SessionUser("u1", "biz1", Role.GYM_STAFF, "Gina", "g@x.com", null, "active", true),
            Business("biz1", "Hotel", "ABC123", setOf(Role.GYM_STAFF), timezone = timezone),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

/** In-memory members and visits with real all-or-nothing transaction behaviour (writes apply only if the block finishes). */
internal class FakeGymTxStore : GymVisitsStore {
    val members = mutableMapOf<String, LiveGymMember>()
    val visits = mutableMapOf<String, Map<String, Any?>>()
    val closed = mutableSetOf<String>()
    var transactions = 0
    private var counter = 0

    override fun observeVisits(): Flow<Resource<List<GymVisit>>> = MutableStateFlow(Resource.Success(emptyList()))
    override fun newVisitId(): String = "v${++counter}"

    override suspend fun <R> inTransaction(block: (GymTx) -> R): R {
        transactions++
        val memberWrites = mutableListOf<Pair<String, Map<String, Any?>>>()
        val visitCreates = mutableListOf<Pair<String, Map<String, Any?>>>()
        val visitUpdates = mutableListOf<Pair<String, Map<String, Any?>>>()
        val tx = object : GymTx {
            override fun readMember(memberId: String): LiveGymMember? = members[memberId]
            override fun readVisit(visitId: String): LiveGymVisit? =
                visits[visitId]?.let { LiveGymVisit(it["memberName"] as String, visitId in closed) }
            override fun createVisit(visitId: String, payload: Map<String, Any?>) { visitCreates += visitId to payload }
            override fun updateVisit(visitId: String, fields: Map<String, Any?>) { visitUpdates += visitId to fields }
            override fun updateMember(memberId: String, fields: Map<String, Any?>) { memberWrites += memberId to fields }
        }
        val result = block(tx) // a throw leaves every buffer unapplied
        visitCreates.forEach { (id, p) -> visits[id] = p }
        visitUpdates.forEach { (id, _) -> closed += id }
        memberWrites.forEach { (id, f) ->
            val m = members.getValue(id)
            members[id] = m.copy(
                activeVisitId = if (f.containsKey("activeVisitId")) f["activeVisitId"] as String? else m.activeVisitId,
                visitCount = (f["visitCount"] as? Int) ?: m.visitCount
            )
        }
        return result
    }
}

internal class FakeGymMembersStore : GymMembersStore {
    val added = mutableListOf<Map<String, Any?>>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    val liveEnds = mutableMapOf<String, Instant?>()
    var renewals = mutableListOf<Map<String, Any?>>()

    override fun observeMembers(): Flow<Resource<List<GymMember>>> = MutableStateFlow(Resource.Success(emptyList()))
    override fun observePackages(): Flow<Resource<List<GymPackage>>> = MutableStateFlow(Resource.Success(emptyList()))
    override suspend fun add(payload: Map<String, Any?>): String { added += payload; return "m-new" }
    override suspend fun update(memberId: String, fields: Map<String, Any?>) { updates += memberId to fields }
    override suspend fun renew(memberId: String, plan: (LiveMembership) -> RenewWrite): RenewResult {
        if (memberId !in liveEnds) throw GymException(MSG_MEMBER_NOT_FOUND)
        val live = LiveMembership("Ada Obi", liveEnds[memberId])
        val write = plan(live)
        renewals += write.fields
        return RenewResult(live, write)
    }
}

internal fun gymMember(
    id: String = "m1", name: String = "Ada Obi", phone: String? = "0803", room: String? = null, status: String = "active",
    endSeconds: Long? = null, activeVisitId: String? = null, deleted: Boolean = false, email: String? = null
) = GymMember(
    id = id, name = name, phone = phone, roomNumber = room, email = email, status = status,
    endDate = endSeconds?.let { Timestamp(it, 0) }, activeVisitId = activeVisitId, isDeleted = deleted
)

internal fun gymVisit(
    id: String, name: String = "Ada Obi", dateKey: String = "2026-10-10", inSeconds: Long? = 1_000, outSeconds: Long? = null, deleted: Boolean = false
) = GymVisit(
    id = id, memberId = "m1", memberName = name, dateKey = dateKey,
    checkInAt = inSeconds?.let { Timestamp(it, 0) }, checkOutAt = outSeconds?.let { Timestamp(it, 0) }, isDeleted = deleted
)
