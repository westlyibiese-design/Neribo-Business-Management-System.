package com.westly.nbms.features.reservations

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

private class SignedOutSession : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(SessionState.SignedOut)
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

private class SignedInSession : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        SessionState.SignedIn(
            SessionUser("u1", "biz1", Role.RECEPTIONIST, "Rita", "r@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(Role.RECEPTIONIST)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

/** Any call on these fails the test: the paths exercised below must stop before touching the database. */
private inline fun <reified T : Any> untouchable(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as T

private fun repo(session: SessionManager) = ReservationsRepository(
    untouchable<BusinessFirestore>(), untouchable<BusinessRealtime>(), untouchable<AuditLogger>(), untouchable<Notifier>(), session
)

private val SOME_BOOKING = Booking(id = "bk1", guestName = "Ada", status = "pending", source = "website")

class ReservationsRepositoryTest {

    @Test fun disallowedStatusesFailWithIllegalArgument() = runTest {
        val r = repo(SignedInSession())
        listOf(BookingStatus.PENDING, BookingStatus.CHECKED_IN, BookingStatus.CHECKED_OUT).forEach { status ->
            val result = r.changeStatus(SOME_BOOKING, status)
            assertTrue(status.key, result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun disallowedStatusIsCheckedBeforeSignIn() = runTest {
        val result = repo(SignedOutSession()).changeStatus(SOME_BOOKING, BookingStatus.CHECKED_IN)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun signedOutChangeStatusFailsWithUserText() = runTest {
        val result = repo(SignedOutSession()).changeStatus(SOME_BOOKING, BookingStatus.CONFIRMED)
        val e = result.exceptionOrNull()
        assertTrue(e is ReservationException)
        assertEquals("You're signed out. Please sign in again.", e?.message)
    }

    @Test fun signedOutCheckInFailsWithUserText() = runTest {
        val form = CheckInForm(Instant.parse("2026-10-09T09:00:00Z"), null, PaymentOption.PAY_AT_CHECK_OUT, PaymentMethod.CASH, null)
        val result = repo(SignedOutSession()).checkIn(SOME_BOOKING, form)
        val e = result.exceptionOrNull()
        assertTrue(e is ReservationException)
        assertEquals("You're signed out. Please sign in again.", e?.message)
    }
}
