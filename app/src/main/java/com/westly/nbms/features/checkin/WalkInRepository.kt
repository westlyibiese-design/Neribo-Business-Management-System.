package com.westly.nbms.features.checkin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomLogic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WalkIn"

// ---- Phone network ---------------------------------------------------------------------------------

/** Is the phone online, and does it lose its connection while we wait? A fake stands in for it in the unit tests. */
interface Connectivity {
    fun isOnline(): Boolean

    /** Emits once each time the connection drops. */
    fun losses(): Flow<Unit>
}

@Singleton
class AndroidConnectivity @Inject constructor(
    @ApplicationContext private val context: Context
) : Connectivity {

    private val manager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isOnline(): Boolean {
        val cm = manager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun losses(): Flow<Unit> = callbackFlow {
        val cm = manager
        if (cm == null) {
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                trySend(Unit)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the network", e)
        }
        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (ignored: Exception) {
                // Never registered.
            }
        }
    }
}

// ---- The one transaction ---------------------------------------------------------------------------

/**
 * The database calls a walk-in needs. [FirestoreWalkInStore] is the real one; the unit tests use a fake.
 * Time values equal to [WalkInServerTime] mean "the server's clock".
 */
interface WalkInStore {
    /** Four fresh document ids (made on the phone, no network needed). */
    fun newIds(): WalkInIds

    /**
     * ONE transaction: read the room (missing → "Room not found.", not `available` → "Room is no longer available.",
     * both as [WalkInException]); then write the guest, booking, per-night lock, check-in record, the room update and,
     * when present, the payment.
     */
    suspend fun commit(writes: WalkInWrites)
}

@Singleton
class FirestoreWalkInStore @Inject constructor(
    private val firestore: BusinessFirestore
) : WalkInStore {

    override fun newIds(): WalkInIds = WalkInIds(
        guestId = firestore.collection("guests").document().id,
        bookingId = firestore.collection("bookings").document().id,
        checkinId = firestore.collection("checkins").document().id,
        paymentId = firestore.collection("payments").document().id
    )

    override suspend fun commit(writes: WalkInWrites) {
        try {
            firestore.runTransaction { tx, fs ->
                // All reads first, then the writes.
                val roomRef = fs.doc("rooms", writes.roomId)
                val roomSnap = tx.get(roomRef)
                roomCheckError(roomSnap.exists(), roomSnap.getString("status"))?.let { throw WalkInException(it) }

                tx.set(fs.doc("guests", writes.ids.guestId), writes.guest.resolveServerTime())
                tx.set(fs.doc("bookings", writes.ids.bookingId), writes.booking.resolveServerTime())
                tx.set(fs.doc("booking_dates", writes.ids.bookingId), writes.bookingDate.resolveServerTime())
                tx.set(fs.doc("checkins", writes.ids.checkinId), writes.checkin.resolveServerTime())
                tx.update(roomRef, writes.room.resolveServerTime())
                writes.payment?.let { tx.set(fs.doc("payments", writes.ids.paymentId), it.resolveServerTime()) }
                Unit
            }
        } catch (e: Exception) {
            // Firestore may wrap what the function threw; hand our own message back untouched.
            throw e.findWalkInException() ?: e
        }
    }
}

private fun Map<String, Any?>.resolveServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === WalkInServerTime) FieldValue.serverTimestamp() else v }

private fun Throwable.findWalkInException(): WalkInException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is WalkInException) return current
        current = current.cause
        depth++
    }
    return null
}

// ---- The repository --------------------------------------------------------------------------------

@Singleton
class WalkInRepository @Inject constructor(
    private val store: WalkInStore,
    private val roomLogic: RoomLogic,
    private val realtime: BusinessRealtime,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val connectivity: Connectivity
) {

    /**
     * Steps 2–9 of the walk-in: network check, room check, date check, conflict check, the transaction, then the
     * best-effort steps. Never throws (except cancellation): a problem comes back as [WalkInResult.Rejected].
     * [onConnectionLost] is called at most once if the connection drops while saving.
     */
    suspend fun submit(
        form: WalkInForm,
        room: Room,
        staff: WalkInStaff,
        zone: TimeZone,
        officialCheckOutTime: String,
        onConnectionLost: () -> Unit = {}
    ): WalkInResult {
        if (!connectivity.isOnline()) return WalkInResult.Rejected(TITLE_OFFLINE, MSG_OFFLINE)
        if (!isAvailable(room)) {
            return WalkInResult.Rejected(TITLE_ROOM_NOT_AVAILABLE, roomNotAvailableMessage(room.number, room.status))
        }
        val stay = validateStay(form.checkInDate, form.checkInTime, form.checkOutDate, officialCheckOutTime, zone)
        val valid = when (stay) {
            is StayValidation.Invalid -> return WalkInResult.Rejected(TITLE_FAILED, stay.message)
            is StayValidation.Valid -> stay
        }

        return coroutineScope {
            val watcher = launch {
                if (connectivity.losses().firstOrNull() != null) onConnectionLost()
            }
            try {
                save(form, room, staff, valid)
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun save(form: WalkInForm, room: Room, staff: WalkInStaff, stay: StayValidation.Valid): WalkInResult {
        try {
            val hasConflict = withTimeoutOrNull(CONFLICT_TIMEOUT_MS) {
                roomLogic.detectConflict(room.id, stay.checkIn, stay.checkOut)
            } ?: throw WalkInException(MSG_CONFLICT_TIMEOUT)
            if (hasConflict) throw WalkInException(MSG_ALREADY_BOOKED)

            val ids = store.newIds()
            val writes = buildWalkInWrites(ids, form, room, staff, stay.checkIn, stay.checkOut, stay.nights)
            val committed = withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
                store.commit(writes)
                true
            }
            if (committed == null) throw WalkInException(MSG_SAVE_TIMEOUT)

            val payAtCheckIn = form.paymentOption == PaymentOption.PAY_AT_CHECKIN
            afterCommit(writes, form, room, staff, stay, payAtCheckIn)

            return WalkInResult.Done(
                WalkInSuccess(
                    guestName = form.fullName.trim(),
                    roomNumber = room.number,
                    checkOut = stay.checkOut,
                    paidNow = payAtCheckIn,
                    total = stayTotal(room.price, stay.nights)
                )
            )
        } catch (e: TimeoutCancellationException) {
            // A time limit inside a lower layer ran out; that is a failed save, not a cancelled screen.
            return WalkInResult.Rejected(TITLE_FAILED, MSG_SAVE_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Walk-in failed", e)
            return WalkInResult.Rejected(TITLE_FAILED, e.message?.takeIf { it.isNotBlank() } ?: MSG_GENERIC)
        }
    }

    /**
     * Step 8: everything after the commit is best effort. Each step has its own guard and time limit, so one
     * failure never undoes or hides the saved walk-in, and a slow connection cannot keep the button spinning.
     */
    private suspend fun afterCommit(
        writes: WalkInWrites,
        form: WalkInForm,
        room: Room,
        staff: WalkInStaff,
        stay: StayValidation.Valid,
        payAtCheckIn: Boolean
    ) {
        val guestName = form.fullName.trim()
        val total = stayTotal(room.price, stay.nights)
        val now = System.currentTimeMillis()

        supervisorScope {
            launch {
                guarded("room status") {
                    realtime.update(
                        "roomStatus/${room.id}",
                        mapOf(
                            "status" to "occupied",
                            "currentGuest" to guestName,
                            "cleanliness" to "clean",
                            "updatedAt" to now
                        )
                    )
                }
            }
            launch {
                guarded("audit") {
                    audit.log(
                        "walk_in_checkin",
                        "bookings",
                        writes.ids.bookingId,
                        null,
                        mapOf(
                            "guestName" to guestName,
                            "roomNumber" to room.number,
                            "checkInAt" to stay.checkIn.toFirebase(),
                            "checkOut" to stay.checkOut.toFirebase()
                        )
                    )
                }
            }
            launch { guarded("walk-in alert") { notifier.notifyWalkIn(guestName, room.number) } }
            launch {
                guarded("activity feed") {
                    realtime.set(
                        "activity_feed/${newPushId(now)}",
                        mapOf(
                            "type" to "walk_in",
                            "text" to activityText(guestName, room.number),
                            "at" to now,
                            "by" to staff.name
                        )
                    )
                }
            }
            if (payAtCheckIn) {
                launch {
                    guarded("payment alert") {
                        notifier.notifyPaymentReceived(total, form.paymentMethod.key.replace('_', ' '), guestName, staff.name)
                    }
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
            Log.w(TAG, "Walk-in $what step failed", e)
        }
    }
}
