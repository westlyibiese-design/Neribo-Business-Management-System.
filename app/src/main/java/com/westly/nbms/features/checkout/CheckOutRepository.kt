package com.westly.nbms.features.checkout

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.features.bookings.Booking
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CheckOut"

// ---- Phone network ---------------------------------------------------------------------------------

/** Is the phone online? A fake stands in for it in the unit tests. */
interface CheckOutNetwork {
    fun isOnline(): Boolean
}

@Singleton
class AndroidCheckOutNetwork @Inject constructor(
    @ApplicationContext private val context: Context
) : CheckOutNetwork {

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

// ---- The one transaction ---------------------------------------------------------------------------

/**
 * The database calls a check-out needs. [FirestoreCheckOutStore] is the real one; the unit tests use a fake.
 * Time values equal to [CheckOutServerTime] mean "the server's clock".
 */
interface CheckOutStore {
    /** Two fresh document ids (made on the phone, no network needed). */
    fun newIds(): CheckOutIds

    /**
     * ONE transaction: read `bookings/{bookingDocId}` (missing -> "Booking not found.", not `checked_in` ->
     * "Booking is not in checked-in state.", both as [CheckOutException]); hand the live booking to [build];
     * then write what [build] returns (booking, per-night lock, room, checkout record and, when money is due,
     * the payment).
     */
    suspend fun commit(bookingDocId: String, build: (LiveBooking) -> CheckOutWrites): CheckOutCommit
}

@Singleton
class FirestoreCheckOutStore @Inject constructor(
    private val firestore: BusinessFirestore
) : CheckOutStore {

    override fun newIds(): CheckOutIds = CheckOutIds(
        checkoutId = firestore.collection("checkouts").document().id,
        paymentId = firestore.collection("payments").document().id
    )

    override suspend fun commit(bookingDocId: String, build: (LiveBooking) -> CheckOutWrites): CheckOutCommit {
        try {
            return firestore.runTransaction { tx, fs ->
                // All reads first, then the writes.
                val bookingRef = fs.doc("bookings", bookingDocId)
                val snap = tx.get(bookingRef)
                bookingCheckError(snap.exists(), snap.getString("status"))?.let { throw CheckOutException(it) }

                val live = snap.toLiveBooking()
                val writes = build(live)

                tx.update(bookingRef, writes.booking.resolveServerTime())
                tx.set(fs.doc("booking_dates", bookingDocId), writes.bookingDate.resolveServerTime(), SetOptions.merge())
                val roomId = writes.roomId
                val room = writes.room
                if (roomId != null && room != null) {
                    tx.update(fs.doc("rooms", roomId), room.resolveServerTime())
                }
                tx.set(fs.doc("checkouts", writes.ids.checkoutId), writes.checkout.resolveServerTime())
                writes.payment?.let { tx.set(fs.doc("payments", writes.ids.paymentId), it.resolveServerTime()) }
                CheckOutCommit(live, writes)
            }
        } catch (e: Exception) {
            // Firestore may wrap what the function threw; hand our own message back untouched.
            throw e.findCause<CheckOutException>() ?: e
        }
    }
}

private fun Map<String, Any?>.resolveServerTime(): Map<String, Any?> =
    mapValues { (_, v) -> if (v === CheckOutServerTime) FieldValue.serverTimestamp() else v }

private fun DocumentSnapshot.toLiveBooking(): LiveBooking = LiveBooking(
    id = id,
    bookingId = getString("bookingId"),
    status = getString("status"),
    guestName = getString("guestName").orEmpty(),
    guestEmail = getString("guestEmail"),
    guestPhone = getString("guestPhone"),
    roomId = getString("roomId").orEmpty(),
    roomNumber = getString("roomNumber").orEmpty(),
    roomType = getString("roomType"),
    checkIn = getTimestamp("checkIn").asInstant(),
    checkInAt = getTimestamp("checkInAt").asInstant(),
    checkOut = getTimestamp("checkOut").asInstant(),
    nights = getLong("nights")?.toInt(),
    totalAmount = getDouble("totalAmount") ?: 0.0,
    paymentMethod = getString("paymentMethod"),
    roomPaymentStatus = getString("roomPaymentStatus"),
    extensions = readExtensions(get("extensionHistory"))
)

/** `extensionHistory` is a list of maps; anything unreadable is skipped. */
private fun readExtensions(raw: Any?): List<ExtensionRecord> =
    (raw as? List<*>).orEmpty().mapNotNull { item ->
        val entry = item as? Map<*, *> ?: return@mapNotNull null
        ExtensionRecord(
            nightsAdded = (entry["nightsAdded"] as? Number)?.toInt() ?: 0,
            amount = (entry["amount"] as? Number)?.toDouble() ?: 0.0,
            newCheckOut = (entry["newCheckOut"] as? Timestamp).asInstant()
        )
    }

// ---- The repository --------------------------------------------------------------------------------

@Singleton
class CheckOutRepository @Inject constructor(
    private val store: CheckOutStore,
    private val realtime: BusinessRealtime,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val network: CheckOutNetwork
) {

    /** Replaced in unit tests. */
    internal var clock: () -> Instant = { Clock.System.now() }

    /**
     * Steps 2–6 of the check-out: network check, date checks, the transaction, then the best-effort steps.
     * Never throws (except cancellation): a problem comes back as [CheckOutResult.Rejected].
     */
    suspend fun checkOut(booking: Booking, input: CheckOutInput): CheckOutResult {
        if (!network.isOnline()) return CheckOutResult.Rejected(TITLE_OFFLINE, MSG_OFFLINE)

        val actual = input.actualAt ?: return CheckOutResult.Rejected(TITLE_CHECKOUT_FAILED, MSG_BAD_DATE)
        val checkInAt = (booking.checkInAt ?: booking.checkIn).asInstant()
        if (checkInAt != null && actual < checkInAt) {
            return CheckOutResult.Rejected(TITLE_CHECKOUT_FAILED, MSG_BEFORE_CHECK_IN)
        }
        return save(booking, input, actual)
    }

    private suspend fun save(booking: Booking, input: CheckOutInput, actual: Instant): CheckOutResult {
        try {
            val ids = store.newIds()
            val commit = withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
                store.commit(booking.id) { live -> buildCheckOutWrites(ids, live, input, actual) }
            } ?: throw CheckOutException(MSG_SAVE_TIMEOUT)

            afterCommit(commit, input, actual)

            val receipt = buildReceipt(
                live = commit.live,
                summary = commit.writes.summary,
                businessName = input.businessName,
                currencySymbol = input.currencySymbol,
                zone = input.zone,
                producedAt = clock()
            )
            return CheckOutResult.Done(
                CheckOutSuccess(
                    guestName = commit.live.guestName,
                    roomNumber = commit.live.roomNumber,
                    summary = commit.writes.summary,
                    receipt = receipt
                )
            )
        } catch (e: TimeoutCancellationException) {
            // A time limit inside a lower layer ran out; that is a failed save, not a cancelled screen.
            return CheckOutResult.Rejected(TITLE_CHECKOUT_FAILED, MSG_SAVE_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Check-out failed", e)
            return CheckOutResult.Rejected(TITLE_CHECKOUT_FAILED, e.message?.takeIf { it.isNotBlank() } ?: MSG_GENERIC)
        }
    }

    /**
     * Step 6: everything after the commit is best effort. Each step has its own guard and time limit, so one
     * failure never undoes or hides the saved check-out, and a slow connection cannot keep the button spinning.
     */
    private suspend fun afterCommit(commit: CheckOutCommit, input: CheckOutInput, actual: Instant) {
        val live = commit.live
        val writes = commit.writes
        val summary = writes.summary
        val staffName = input.staff.name
        val now = System.currentTimeMillis()

        supervisorScope {
            val roomId = writes.roomId
            if (roomId != null) {
                launch {
                    guarded("room status") {
                        realtime.update(
                            "roomStatus/$roomId",
                            mapOf(
                                "status" to "cleaning",
                                "cleanliness" to "dirty",
                                // Phase 13 puts the guest's name here at check-in; null removes it.
                                "currentGuest" to null,
                                "updatedAt" to now
                            )
                        )
                    }
                }
            }
            launch {
                guarded("audit") {
                    audit.log(
                        "check_out",
                        "bookings",
                        live.id,
                        mapOf("status" to "checked_in"),
                        mapOf(
                            "status" to "checked_out",
                            "checkOutAt" to actual.toFirebase(),
                            "finalAmount" to summary.finalAmount
                        )
                    )
                }
            }
            launch { guarded("check-out alert") { notifier.notifyCheckOut(live.guestName, live.roomNumber, staffName) } }
            if (summary.amountToCharge > 0.0) {
                launch {
                    guarded("payment alert") {
                        notifier.notifyPaymentReceived(
                            summary.amountToCharge,
                            summary.paymentMethodKey.replace('_', ' '),
                            live.guestName,
                            staffName
                        )
                    }
                }
            }
            launch {
                guarded("activity feed") {
                    realtime.set(
                        "activity_feed/${newPushId(now)}",
                        mapOf(
                            "type" to "check_out",
                            "text" to activityText(staffName, live.guestName, live.roomNumber),
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
            Log.w(TAG, "Check-out $what step failed", e)
        }
    }
}
