package com.westly.nbms.features.checkout

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.rooms.RoomLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtendStay"

// ---- Models ----------------------------------------------------------------------------------------

/** What the dialog asks for. */
data class ExtendRequest(
    val extraNights: Int,
    val method: ExtendPaymentMethod,
    val role: Role?,
    val staff: CheckOutStaff,
    val zone: TimeZone
)

/** The booking and its room exactly as the transaction finds them (live documents, not the list on screen). */
data class LiveStay(
    val id: String,
    val status: String?,
    val guestName: String,
    val roomId: String,
    val roomNumber: String,
    val checkIn: Instant?,
    val checkOut: Instant?,
    val nights: Int?,
    val pricePerNight: Double?,
    val totalAmount: Double,
    val roomPrice: Double?,
    val roomCheckoutOverdue: Boolean
)

/** The result of the maths: used for the alerts, the audit entry and the toast. */
data class ExtendSummary(
    val guestName: String,
    val roomNumber: String,
    val previousCheckOut: Instant,
    val newCheckOut: Instant,
    val nightsAdded: Int,
    val amount: Double,
    val methodKey: String
)

/** Stands for `FieldValue.arrayUnion(entry)` inside the booking update (the real store makes the real one). */
internal class ExtendArrayUnion(val entry: Map<String, Any?>)

/** Every document the one transaction writes. [payment] is null when the extension costs nothing. */
data class ExtendWrites(
    val bookingDocId: String,
    val paymentId: String,
    val roomId: String,
    val booking: Map<String, Any?>,
    val bookingDate: Map<String, Any?>,
    /** True when the room carries the "checkout overdue" flag, which an extension clears. */
    val clearRoomOverdue: Boolean,
    val payment: Map<String, Any?>?,
    val summary: ExtendSummary
)

/** A problem with a clear sentence for the person at the desk (shown in the "Extension Failed" toast). */
class ExtendStayException(message: String) : Exception(message)

sealed interface ExtendResult {
    /** [newCheckOutText] is the new check-out date as "MMM d, yyyy". */
    data class Done(val roomNumber: String, val newCheckOutText: String) : ExtendResult

    /** Nothing was saved (or saving failed); show a toast with [title] and [message]. */
    data class Rejected(val title: String, val message: String) : ExtendResult
}

// ---- The documents of the transaction --------------------------------------------------------------

/**
 * Works everything out from the live documents: the live check-out and the live rate. `checkIn` on the booking
 * is never touched. [now] stamps `extendedAt` (a server timestamp is not allowed inside an array).
 */
internal fun buildExtendWrites(paymentId: String, live: LiveStay, request: ExtendRequest, now: Instant): ExtendWrites {
    val previous = live.checkOut ?: throw ExtendStayException(MSG_NO_CHECK_OUT)
    val n = request.extraNights
    val zone = request.zone
    val staff = request.staff
    val rate = nightlyRate(live.pricePerNight, live.totalAmount, live.nights, live.roomPrice)
    val amount = additionalAmount(rate, n)
    val newCheckOut = addDays(previous, n, zone)
    val previousNights = live.nights ?: nightsBetween(live.checkIn, previous, zone) ?: 0
    val previousStamp = previous.toFirebase()
    val newStamp = newCheckOut.toFirebase()

    val entry = mapOf(
        "extendedAt" to now.toFirebase(),
        "extendedBy" to staff.uid,
        "extendedByName" to staff.name,
        "nightsAdded" to n,
        "previousCheckOut" to previousStamp,
        "newCheckOut" to newStamp,
        "amount" to amount
    )

    val booking = mapOf(
        "checkOut" to newStamp,
        "nights" to previousNights + n,
        "totalAmount" to live.totalAmount + amount,
        "extensionHistory" to ExtendArrayUnion(entry),
        "lastOverdueNotifiedAt" to null,
        "updatedAt" to CheckOutServerTime,
        "updatedBy" to staff.uid,
        "updatedByName" to staff.name
    )

    val bookingDate = buildMap<String, Any?> {
        put("roomId", live.roomId)
        live.checkIn?.let { put("checkIn", it.toFirebase()) }
        put("checkOut", newStamp)
        put("status", "checked_in")
    }

    val payment = if (amount > 0.0) {
        mapOf(
            "bookingId" to live.id,
            "guestName" to live.guestName,
            "roomNumber" to live.roomNumber,
            "amount" to amount,
            "paymentMethod" to request.method.key,
            "type" to "stay_extension",
            "recordedBy" to staff.uid,
            "recordedByName" to staff.name,
            "createdAt" to CheckOutServerTime,
            "approvalStatus" to "pending",
            "approvedBy" to null,
            "approvedByName" to null,
            "approvedAt" to null,
            "rejectedReason" to null,
            "isDeleted" to false,
            "extensionNights" to n,
            "previousCheckOut" to previousStamp,
            "newCheckOut" to newStamp
        )
    } else null

    val summary = ExtendSummary(
        guestName = live.guestName,
        roomNumber = live.roomNumber,
        previousCheckOut = previous,
        newCheckOut = newCheckOut,
        nightsAdded = n,
        amount = amount,
        methodKey = request.method.key
    )
    return ExtendWrites(live.id, paymentId, live.roomId, booking, bookingDate, live.roomCheckoutOverdue, payment, summary)
}

// ---- The store -------------------------------------------------------------------------------------

/**
 * The database calls an extension needs. [FirestoreExtendStayStore] is the real one; the unit tests use a fake.
 */
interface ExtendStayStore {
    /** A fresh payment document id (made on the phone, no network needed). */
    fun newPaymentId(): String

    /**
     * ONE transaction: read the booking and its room (reads before writes); missing booking, a booking that is not
     * `checked_in`, a missing room or a room that belongs to another booking each stop with an [ExtendStayException];
     * hand the live documents to [build]; then write what it returns.
     */
    suspend fun commit(bookingDocId: String, build: (LiveStay) -> ExtendWrites): ExtendWrites
}

@Singleton
class FirestoreExtendStayStore @Inject constructor(
    private val firestore: BusinessFirestore
) : ExtendStayStore {

    override fun newPaymentId(): String = firestore.collection("payments").document().id

    override suspend fun commit(bookingDocId: String, build: (LiveStay) -> ExtendWrites): ExtendWrites {
        try {
            return firestore.runTransaction { tx, fs ->
                // All reads first (booking, then its room), then the writes.
                val bookingRef = fs.doc("bookings", bookingDocId)
                val bookingSnap = tx.get(bookingRef)
                stayCheckError(bookingSnap.exists(), bookingSnap.getString("status"))
                    ?.let { throw ExtendStayException(it) }

                val roomId = bookingSnap.getString("roomId").orEmpty()
                val roomRef = if (roomId.isBlank()) null else fs.doc("rooms", roomId)
                val roomSnap = roomRef?.let { tx.get(it) }
                roomAssignmentError(roomSnap?.exists() == true, roomSnap?.getString("currentBookingId"), bookingDocId)
                    ?.let { throw ExtendStayException(it) }

                val live = toLiveStay(bookingSnap, roomSnap)
                val writes = build(live)

                tx.update(bookingRef, writes.booking.resolveMarkers())
                tx.set(fs.doc("booking_dates", bookingDocId), writes.bookingDate.resolveMarkers(), SetOptions.merge())
                if (writes.clearRoomOverdue && roomRef != null) {
                    tx.update(roomRef, mapOf("checkoutOverdue" to false))
                }
                writes.payment?.let { tx.set(fs.doc("payments", writes.paymentId), it.resolveMarkers()) }
                writes
            }
        } catch (e: Exception) {
            // Firestore may wrap what the function threw; hand our own message back untouched.
            throw e.findCause<ExtendStayException>() ?: e
        }
    }
}

private fun toLiveStay(booking: DocumentSnapshot, room: DocumentSnapshot?): LiveStay = LiveStay(
    id = booking.id,
    status = booking.getString("status"),
    guestName = booking.getString("guestName").orEmpty(),
    roomId = booking.getString("roomId").orEmpty(),
    roomNumber = booking.getString("roomNumber").orEmpty(),
    checkIn = booking.getTimestamp("checkIn").asInstant(),
    checkOut = booking.getTimestamp("checkOut").asInstant(),
    nights = booking.getLong("nights")?.toInt(),
    pricePerNight = booking.getDouble("pricePerNight"),
    totalAmount = booking.getDouble("totalAmount") ?: 0.0,
    roomPrice = room?.getDouble("price"),
    roomCheckoutOverdue = room?.getBoolean("checkoutOverdue") == true
)

private fun Map<String, Any?>.resolveMarkers(): Map<String, Any?> = mapValues { (_, v) ->
    when {
        v === CheckOutServerTime -> FieldValue.serverTimestamp()
        v is ExtendArrayUnion -> FieldValue.arrayUnion(v.entry)
        else -> v
    }
}

// ---- The repository --------------------------------------------------------------------------------

@Singleton
class ExtendStayRepository @Inject constructor(
    private val store: ExtendStayStore,
    private val roomLogic: RoomLogic,
    private val realtime: BusinessRealtime,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val network: CheckOutNetwork
) {

    /** Replaced in unit tests. */
    internal var clock: () -> Instant = { Clock.System.now() }

    /**
     * The confirm steps of the Extend Stay dialog: role, network, night count, check-out date, room conflict, the
     * transaction, then the best-effort steps. Never throws (except cancellation): a problem comes back as
     * [ExtendResult.Rejected].
     */
    suspend fun extend(booking: Booking, request: ExtendRequest): ExtendResult {
        if (!mayExtend(request.role)) return ExtendResult.Rejected(TITLE_NOT_AUTHORIZED, MSG_NOT_AUTHORIZED)
        if (!network.isOnline()) return ExtendResult.Rejected(TITLE_OFFLINE, MSG_OFFLINE)
        if (request.extraNights < 1) return ExtendResult.Rejected(TITLE_INVALID_EXTENSION, MSG_INVALID_EXTENSION)
        val currentCheckOut = booking.checkOut.asInstant()
            ?: return ExtendResult.Rejected(TITLE_EXTEND_FAILED, MSG_NO_CHECK_OUT)

        try {
            val newCheckOut = addDays(currentCheckOut, request.extraNights, request.zone)
            val hasConflict = withTimeoutOrNull(CONFLICT_TIMEOUT_MS) {
                roomLogic.detectConflict(booking.roomId, currentCheckOut, newCheckOut, excludeBookingId = booking.id)
            } ?: throw ExtendStayException(MSG_CONFLICT_TIMEOUT)
            if (hasConflict) throw ExtendStayException(MSG_EXTEND_CONFLICT)

            val paymentId = store.newPaymentId()
            val writes = withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
                store.commit(booking.id) { live -> buildExtendWrites(paymentId, live, request, clock()) }
            } ?: throw ExtendStayException(MSG_SAVE_TIMEOUT)

            afterCommit(writes, request)
            val summary = writes.summary
            return ExtendResult.Done(summary.roomNumber, mmmDYyyy(summary.newCheckOut, request.zone))
        } catch (e: TimeoutCancellationException) {
            return ExtendResult.Rejected(TITLE_EXTEND_FAILED, MSG_SAVE_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Extend stay failed", e)
            return ExtendResult.Rejected(TITLE_EXTEND_FAILED, e.message?.takeIf { it.isNotBlank() } ?: MSG_GENERIC)
        }
    }

    /** Everything after the commit is best effort, each step with its own guard and time limit. */
    private suspend fun afterCommit(writes: ExtendWrites, request: ExtendRequest) {
        val s = writes.summary
        val staffName = request.staff.name
        val now = System.currentTimeMillis()

        supervisorScope {
            launch {
                guarded("audit") {
                    audit.log(
                        "stay_extended",
                        "bookings",
                        writes.bookingDocId,
                        mapOf("checkOut" to s.previousCheckOut.toFirebase()),
                        mapOf(
                            "checkOut" to s.newCheckOut.toFirebase(),
                            "nightsAdded" to s.nightsAdded,
                            "amount" to s.amount
                        )
                    )
                }
            }
            launch {
                guarded("stay-extended alert") {
                    notifier.notifyStayExtended(s.guestName, s.roomNumber, mmmDYyyy(s.newCheckOut, request.zone), staffName)
                }
            }
            if (s.amount > 0.0) {
                launch {
                    guarded("payment alert") {
                        notifier.notifyPaymentReceived(s.amount, s.methodKey.replace('_', ' '), s.guestName, staffName)
                    }
                }
            }
            launch {
                guarded("activity feed") {
                    realtime.set(
                        "activity_feed/${newPushId(now)}",
                        mapOf(
                            "type" to "booking_modified",
                            "text" to extendActivityText(staffName, s.guestName, s.roomNumber, s.nightsAdded),
                            "at" to now,
                            "by" to staffName
                        )
                    )
                }
            }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(POST_STEP_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Extend stay $what step failed", e)
        }
    }
}
