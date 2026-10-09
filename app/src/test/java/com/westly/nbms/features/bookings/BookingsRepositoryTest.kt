package com.westly.nbms.features.bookings

import com.google.firebase.Timestamp
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

private class FakeStore : BookingStore {
    /** One entry per batch: the booking fields and the lock fields travel together. */
    data class Commit(val id: String, val booking: Map<String, Any?>, val lock: Map<String, Any?>)

    val commits = mutableListOf<Commit>()
    var fail: Exception? = null

    override suspend fun commitStatusChange(bookingId: String, bookingFields: Map<String, Any?>, lockFields: Map<String, Any?>) {
        fail?.let { throw it }
        commits += Commit(bookingId, bookingFields, lockFields)
    }
}

private class FakeAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val id: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)

    val entries = mutableListOf<Entry>()
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

private class FakeNotifier : Notifier {
    data class Call(val type: String, val title: String, val message: String)

    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(type, title, message)
    }
}

private class FakeSession(role: Role?) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (role == null) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Rita", "r@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role)),
            emptySet<ModuleKey>()
        )
    )

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

/** The repository only uses BusinessFirestore for the live streams, which these tests do not touch. */
private fun unusedFirestore(): BusinessFirestore =
    Proxy.newProxyInstance(BusinessFirestore::class.java.classLoader, arrayOf(BusinessFirestore::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as BusinessFirestore

class BookingsRepositoryTest {

    private val store = FakeStore()
    private val audit = FakeAudit()
    private val notifier = FakeNotifier()

    private fun repo(role: Role? = Role.RECEPTIONIST) =
        BookingsRepository(store, unusedFirestore(), audit, notifier, FakeSession(role))

    private fun booking(status: String = "pending") = Booking(
        id = "b1", bookingId = "WI-AB12CD", guestName = "Ada Obi", roomId = "r1", roomNumber = "101", roomType = "Deluxe Room",
        checkIn = Timestamp(100, 0), checkOut = Timestamp(200, 0), status = status
    )

    @Test fun confirmWritesBookingAndLockInOneBatch() = runTest {
        val result = repo().changeStatus(booking("pending"), BookingStatus.CONFIRMED)
        assertTrue(result.isSuccess)
        assertEquals(1, store.commits.size)
        val c = store.commits.single()
        assertEquals("b1", c.id)
        assertEquals("confirmed", c.booking["status"])
        assertEquals("u1", c.booking["updatedBy"])
        assertEquals("Rita", c.booking["updatedByName"])
        assertTrue(c.booking["updatedAt"] === ServerTime)
        assertEquals("r1", c.lock["roomId"])
        assertEquals(Timestamp(100, 0), c.lock["checkIn"])
        assertEquals(Timestamp(200, 0), c.lock["checkOut"])
        assertEquals("confirmed", c.lock["status"])
    }

    @Test fun auditEntryNamesBothStatuses() = runTest {
        repo().changeStatus(booking("confirmed"), BookingStatus.CANCELLED)
        val e = audit.entries.single()
        assertEquals("booking_status_changed:confirmed→cancelled", e.action)
        assertEquals("bookings", e.collection)
        assertEquals("b1", e.id)
        assertEquals(mapOf("status" to "confirmed"), e.previous)
        assertEquals(mapOf("status" to "cancelled"), e.new)
    }

    @Test fun cancelSendsTheCancelledAlert() = runTest {
        repo().changeStatus(booking("confirmed"), BookingStatus.CANCELLED)
        val call = notifier.calls.single()
        assertEquals("Booking Cancelled", call.title)
        assertTrue(call.message.contains("Ada Obi's Deluxe Room booking was cancelled by Rita"))
    }

    @Test fun cancelWithoutRoomTypeSaysRoom() = runTest {
        repo().changeStatus(booking("confirmed").copy(roomType = null), BookingStatus.CANCELLED)
        assertTrue(notifier.calls.single().message.contains("Ada Obi's room booking"))
    }

    @Test fun otherChangesSendTheModifiedAlert() = runTest {
        repo().changeStatus(booking("pending"), BookingStatus.REJECTED)
        val call = notifier.calls.single()
        assertEquals("Booking Modified", call.title)
        assertTrue(call.message.contains("(pending → rejected)"))
    }

    @Test fun savedChangeSurvivesAFailingAlert() = runTest {
        notifier.fail = true
        val result = repo().changeStatus(booking("pending"), BookingStatus.CONFIRMED)
        assertTrue(result.isSuccess)
        assertEquals(1, store.commits.size)
        assertEquals(1, audit.entries.size)
    }

    @Test fun failedBatchReportsTheErrorAndLogsNothing() = runTest {
        store.fail = IllegalStateException("Firestore is down")
        val result = repo().changeStatus(booking("pending"), BookingStatus.CONFIRMED)
        assertEquals("Firestore is down", result.exceptionOrNull()?.message)
        assertTrue(store.commits.isEmpty())
        assertTrue(audit.entries.isEmpty())
        assertTrue(notifier.calls.isEmpty())
    }

    @Test fun notAllowedTransitionIsRefusedBeforeAnyWrite() = runTest {
        val result = repo().changeStatus(booking("pending"), BookingStatus.NO_SHOW)
        assertTrue(result.isFailure)
        assertTrue(store.commits.isEmpty())
        val done = repo().changeStatus(booking("checked_out"), BookingStatus.CANCELLED)
        assertTrue(done.isFailure)
        assertTrue(store.commits.isEmpty())
    }

    @Test fun unknownStatusIsRefused() = runTest {
        assertTrue(repo().changeStatus(booking("weird"), BookingStatus.CONFIRMED).isFailure)
        assertTrue(store.commits.isEmpty())
    }

    @Test fun operationsManagerCannotChangeStatus() = runTest {
        val result = repo(Role.OPERATIONS_MANAGER).changeStatus(booking("pending"), BookingStatus.CONFIRMED)
        assertTrue(result.isFailure)
        assertTrue(store.commits.isEmpty())
    }

    @Test fun signedOutChangesNothing() = runTest {
        val result = repo(null).changeStatus(booking("pending"), BookingStatus.CONFIRMED)
        assertEquals("Not signed in", result.exceptionOrNull()?.message)
        assertTrue(store.commits.isEmpty())
        assertNull(audit.entries.firstOrNull())
    }

    @Test fun managerAndSuperAdminMayChangeStatus() = runTest {
        assertTrue(repo(Role.MANAGER).changeStatus(booking("pending"), BookingStatus.CONFIRMED).isSuccess)
        assertTrue(repo(Role.SUPER_ADMIN).changeStatus(booking("confirmed"), BookingStatus.NO_SHOW).isSuccess)
        assertEquals(2, store.commits.size)
    }
}
