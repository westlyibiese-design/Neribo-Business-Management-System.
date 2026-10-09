package com.westly.nbms.features.reservations

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.bookings.BookingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Reservations"

private const val TRANSACTION_TIMEOUT_MS = 20_000L
private const val SETTINGS_TIMEOUT_MS = 5_000L
private const val REALTIME_TIMEOUT_MS = 8_000L
private const val POST_STEP_TIMEOUT_MS = 8_000L

private const val MSG_SIGNED_OUT = "You're signed out. Please sign in again."
private const val MSG_CHECK_IN_TIMEOUT = "Check-in timed out. Please check your connection and try again."
private const val MSG_CHECK_IN_FAILED = "Could not check in the guest. Please try again."
private const val MSG_STATUS_FAILED = "Could not update the reservation. Please try again."
private const val MSG_STATUS_NOT_ALLOWED = "That status change is not allowed."

/** Everything the Room Reservations screen writes: the status change batch and the check-in transaction. */
@Singleton
class ReservationsRepository @Inject constructor(
    private val firestore: BusinessFirestore,
    private val realtime: BusinessRealtime,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val session: SessionManager
) : ReservationsService {

    // ---- Settings ----------------------------------------------------------------------------------

    override suspend fun loadCheckOutTime(): String = try {
        withTimeoutOrNull(SETTINGS_TIMEOUT_MS) {
            normalizeCheckOutTime(firestore.doc("settings", "hotel").get().await().getString("checkOutTime"))
        } ?: DEFAULT_OFFICIAL_CHECK_OUT
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DEFAULT_OFFICIAL_CHECK_OUT
    }

    // ---- Status change -----------------------------------------------------------------------------

    override suspend fun changeStatus(booking: Booking, newStatus: BookingStatus): Result<Unit> {
        if (newStatus !in CHANGEABLE_STATUSES) {
            return Result.failure(IllegalArgumentException(MSG_STATUS_NOT_ALLOWED))
        }
        val signedIn = currentSession() ?: return Result.failure(ReservationException(MSG_SIGNED_OUT))
        val staffId = signedIn.user.uid
        val staffName = signedIn.user.name
        val previous = booking.status

        try {
            val bookingRef = firestore.doc("bookings", booking.id)
            val datesRef = firestore.doc("booking_dates", booking.id)
            val batch = bookingRef.firestore.batch()
            batch.update(bookingRef, buildStatusChangeBookingUpdate(newStatus, staffId, staffName, FieldValue.serverTimestamp()))
            batch.set(datesRef, buildStatusChangeBookingDates(booking, newStatus), SetOptions.merge())
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Status change failed", e)
            return Result.failure(ReservationException(userMessage(e, MSG_STATUS_FAILED)))
        }

        guarded("status audit") {
            audit.log(
                "booking_status_changed:$previous→${newStatus.key}",
                "bookings",
                booking.id,
                mapOf("status" to previous),
                mapOf("status" to newStatus.key)
            )
        }
        guarded("status alert") {
            when (newStatus) {
                BookingStatus.CONFIRMED -> notifier.notifyBookingApproval(booking.guestName, true, staffName)
                BookingStatus.REJECTED -> notifier.notifyBookingApproval(booking.guestName, false, staffName)
                BookingStatus.CANCELLED -> notifier.notifyBookingCancelled(booking.guestName, booking.roomType ?: "room", staffName)
                BookingStatus.NO_SHOW -> notifier.notifyBookingModified(booking.guestName, "$previous → ${newStatus.key}", staffName)
                else -> Unit
            }
        }
        return Result.success(Unit)
    }

    // ---- Check-in ----------------------------------------------------------------------------------

    private class Committed(val current: Booking, val entitledCheckOut: kotlinx.datetime.Instant)

    override suspend fun checkIn(booking: Booking, form: CheckInForm): Result<CheckInOutcome> {
        val signedIn = currentSession() ?: return Result.failure(ReservationException(MSG_SIGNED_OUT))
        val staffId = signedIn.user.uid
        val staffName = signedIn.user.name
        val zone = zoneOf(signedIn.business.timezone)
        val payNow = isPayNow(form)

        val committed = try {
            withTimeout(TRANSACTION_TIMEOUT_MS) { commitCheckIn(booking, form, staffId, staffName, zone) }
        } catch (e: TimeoutCancellationException) {
            return Result.failure(ReservationException(MSG_CHECK_IN_TIMEOUT))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Check-in failed", e)
            return Result.failure(ReservationException(userMessage(e, MSG_CHECK_IN_FAILED)))
        }

        afterCheckInCommit(committed, form, staffName, payNow)

        val current = committed.current
        return Result.success(
            CheckInOutcome(
                guestName = current.guestName,
                roomNumber = current.roomNumber,
                roomType = current.roomType,
                checkInAt = form.checkInAt,
                entitledCheckOut = committed.entitledCheckOut,
                totalAmount = current.totalAmount,
                paidNow = payNow
            )
        )
    }

    /** Step 2: one transaction. All reads first, then the writes. */
    private suspend fun commitCheckIn(
        booking: Booking,
        form: CheckInForm,
        staffId: String,
        staffName: String,
        zone: ZoneId
    ): Committed {
        val officialTime = loadCheckOutTime()
        val checkinId = firestore.collection("checkins").document().id
        val paymentId = if (isPayNow(form)) firestore.collection("payments").document().id else null

        try {
            return firestore.runTransaction { tx, fs ->
                val bookingRef = fs.doc("bookings", booking.id)
                val snap = tx.get(bookingRef)
                val current = if (snap.exists()) snap.toObject(Booking::class.java) else null
                checkInGuardMessage(current != null, current?.status)?.let { throw ReservationException(it) }
                if (current == null) throw ReservationException(MSG_BOOKING_NOT_FOUND)

                val entitled = ReservationRules.entitledCheckOut(
                    form.checkInAt, ReservationRules.nightsPaid(current), officialTime, zone
                ) ?: throw ReservationException(MSG_NO_ENTITLED)

                val roomId = checkInRoomId(current)
                tx.update(
                    bookingRef,
                    buildCheckInBookingUpdate(form, entitled, staffId, staffName, current.paymentMethod, FieldValue.serverTimestamp())
                )
                tx.set(fs.doc("booking_dates", booking.id), buildCheckInBookingDates(current, entitled), SetOptions.merge())
                tx.update(fs.doc("rooms", roomId), buildCheckInRoomUpdate(current, FieldValue.serverTimestamp()))
                tx.set(
                    fs.doc("checkins", checkinId),
                    buildCheckInDocument(current, form, entitled, staffId, staffName, FieldValue.serverTimestamp())
                )
                if (paymentId != null) {
                    buildCheckInPayment(current, form, staffId, staffName, FieldValue.serverTimestamp())?.let {
                        tx.set(fs.doc("payments", paymentId), it)
                    }
                }
                Committed(current, entitled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Firestore may wrap what the block threw; hand our own message back untouched.
            throw e.findReservationException() ?: e
        }
    }

    /** Step 3: everything after the commit is best effort. One failure never fails the check-in. */
    private suspend fun afterCheckInCommit(committed: Committed, form: CheckInForm, staffName: String, payNow: Boolean) {
        val current = committed.current
        val roomId = checkInRoomId(current)
        val now = System.currentTimeMillis()

        supervisorScope {
            launch {
                guarded("room status", REALTIME_TIMEOUT_MS) {
                    realtime.set("roomStatus/$roomId", buildRoomStatusRealtime(current.guestName, now))
                }
            }
            launch {
                guarded("audit") {
                    audit.log(
                        "check_in",
                        "bookings",
                        current.id,
                        buildCheckInAuditBefore(current.checkOut),
                        buildCheckInAuditAfter(form.checkInAt, committed.entitledCheckOut)
                    )
                }
            }
            launch { guarded("check-in alert") { notifier.notifyCheckIn(current.guestName, current.roomNumber, staffName) } }
            if (payNow) {
                launch {
                    guarded("payment alert") {
                        notifier.notifyPaymentReceived(
                            current.totalAmount, form.paymentMethod.key.replace('_', ' '), current.guestName, staffName
                        )
                    }
                }
            }
            launch {
                guarded("activity feed") {
                    realtime.set(
                        "activity_feed/${newActivityPushId(now)}",
                        buildCheckInActivityItem(staffName, current.guestName, current.roomNumber, now)
                    )
                }
            }
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private fun currentSession(): SessionState.SignedIn? = session.state.value as? SessionState.SignedIn

    private fun zoneOf(id: String?): ZoneId = try {
        if (id.isNullOrBlank()) ZoneId.of("Africa/Lagos") else ZoneId.of(id)
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }

    private fun userMessage(e: Throwable, fallback: String): String =
        (e.findReservationException() ?: (e as? ReservationException))?.message?.takeIf { it.isNotBlank() } ?: fallback

    private suspend fun guarded(what: String, timeoutMs: Long = POST_STEP_TIMEOUT_MS, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(timeoutMs) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reservation $what step failed", e)
        }
    }
}

private fun Throwable.findReservationException(): ReservationException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is ReservationException) return current
        current = current.cause
        depth++
    }
    return null
}
