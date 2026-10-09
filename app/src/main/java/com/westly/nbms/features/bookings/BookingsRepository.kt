package com.westly.nbms.features.bookings

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one database call a status change needs. [FirestoreBookingStore] is the real one; the unit tests use a fake.
 * Both documents are written together or not at all.
 */
/** Stands for "the server's clock" inside the fields handed to a [BookingStore]; the real store turns it into a Firestore server timestamp. */
internal object ServerTime

interface BookingStore {
    /** In ONE batch: update `bookings/{bookingId}` with [bookingFields] and set(merge) `booking_dates/{bookingId}` with [lockFields]. */
    suspend fun commitStatusChange(bookingId: String, bookingFields: Map<String, Any?>, lockFields: Map<String, Any?>)
}

@Singleton
class FirestoreBookingStore @Inject constructor(
    private val firestore: BusinessFirestore
) : BookingStore {
    override suspend fun commitStatusChange(
        bookingId: String,
        bookingFields: Map<String, Any?>,
        lockFields: Map<String, Any?>
    ) {
        val bookingRef = firestore.doc("bookings", bookingId)
        val lockRef = firestore.doc("booking_dates", bookingId)
        val batch = bookingRef.firestore.batch()
        batch.update(bookingRef, bookingFields.mapValues { (_, v) -> if (v === ServerTime) FieldValue.serverTimestamp() else v })
        batch.set(lockRef, lockFields, SetOptions.merge())
        batch.commit().await()
    }
}

@Singleton
class BookingsRepository @Inject constructor(
    private val store: BookingStore,
    private val firestore: BusinessFirestore,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val session: SessionManager
) {
    /** Every booking of this business, live (deleted ones included; the screens filter them). */
    fun observeBookings(): Flow<Resource<List<Booking>>> = firestore.observeList("bookings", Booking::class.java)

    /** Every guest of this business, live. */
    fun observeGuests(): Flow<Resource<List<Guest>>> = firestore.observeList("guests", Guest::class.java)

    /**
     * Moves [booking] to [newStatus]: one batch writes the booking and its per-night lock, then the audit log and the
     * alert follow. A failed audit entry or alert never turns a saved change into a failure.
     */
    suspend fun changeStatus(booking: Booking, newStatus: BookingStatus): Result<Unit> {
        val signedIn = session.state.value as? SessionState.SignedIn
            ?: return Result.failure(IllegalStateException("Not signed in"))
        val user = signedIn.user
        if (!canChangeStatus(user.role)) {
            return Result.failure(IllegalStateException("You do not have permission to change booking status."))
        }
        val previous = BookingStatus.fromKey(booking.status)
            ?: return Result.failure(IllegalStateException("This booking has an unknown status."))
        if (!canTransition(previous, newStatus)) {
            return Result.failure(
                IllegalStateException("A ${statusWords(previous.key)} booking cannot be changed to ${statusWords(newStatus.key)}.")
            )
        }
        try {
            store.commitStatusChange(
                bookingId = booking.id,
                bookingFields = bookingStatusFields(newStatus, ServerTime, user.uid, user.name),
                lockFields = bookingLockFields(booking, newStatus)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }

        audit.log(
            "booking_status_changed:${previous.key}→${newStatus.key}",
            "bookings",
            booking.id,
            mapOf("status" to previous.key),
            mapOf("status" to newStatus.key)
        )
        try {
            if (newStatus == BookingStatus.CANCELLED) {
                notifier.notifyBookingCancelled(booking.guestName, booking.roomType ?: "room", user.name)
            } else if (sendsModifiedAlert(newStatus)) {
                notifier.notifyBookingModified(booking.guestName, "${previous.key} → ${newStatus.key}", user.name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // The change is saved; a failed alert must not look like a failed change.
        }
        return Result.success(Unit)
    }
}
