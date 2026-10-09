package com.westly.nbms.features.laundry

// Pure laundry rules for Manage Laundry (Part 22A): status chain, counts, ordering, form checks and the exact Firestore payloads.



internal const val MSG_LAUNDRY_LOAD_FAILED = "We couldn't load laundry requests."
internal const val MSG_LAUNDRY_NOT_SIGNED_IN = "You are signed out. Please sign in again."
internal const val MSG_LAUNDRY_TIMEOUT = "Saving took too long. Check your connection and try again."
internal const val MSG_LAUNDRY_GENERIC = "Something went wrong. Please try again."
internal const val LAUNDRY_PIN_SIGN_OUT_DELAY_MS = 2_500L

/** Marker the real store swaps for the server's time when it writes. Keeps the payloads testable without Firebase. */
internal object LaundryServerTime

/** The five steps a request moves through, in order (Cancelled is not a step). */
internal val LAUNDRY_ACTIVE_STATUSES: List<LaundryStatus> = listOf(
    LaundryStatus.RECEIVED, LaundryStatus.WASHING, LaundryStatus.DRYING, LaundryStatus.IRONING, LaundryStatus.READY
)

/** The six cards of the summary strip, in order. */
internal val LAUNDRY_SUMMARY_STATUSES: List<LaundryStatus> = LAUNDRY_ACTIVE_STATUSES + LaundryStatus.DELIVERED

private val LAUNDRY_CHAIN: List<LaundryStatus> = LAUNDRY_SUMMARY_STATUSES

/** The next entry in the chain, or null after Delivered (and null for Cancelled). */
fun nextStatus(s: LaundryStatus): LaundryStatus? {
    val i = LAUNDRY_CHAIN.indexOf(s)
    return if (i < 0) null else LAUNDRY_CHAIN.getOrNull(i + 1)
}

/** Active = not delivered and not cancelled. */
fun laundryIsActive(r: LaundryRequest): Boolean = r.status != LaundryStatus.DELIVERED && r.status != LaundryStatus.CANCELLED

/** Label of the row button: "Mark Washing". Null when there is no next step. */
fun laundryNextButtonLabel(s: LaundryStatus): String? = nextStatus(s)?.let { "Mark ${it.label}" }

/** Active requests, oldest first (a request with no time yet goes last; ties keep a stable order by id). */
fun activeRequestsOldestFirst(all: List<LaundryRequest>): List<LaundryRequest> =
    all.filter { laundryIsActive(it) }.sortedWith(
        compareBy<LaundryRequest> { it.createdAt == null }.thenBy { it.createdAt }.thenBy { it.id }
    )

/** Counts for the six cards: the five active statuses over active requests, Delivered over everything loaded. */
fun laundryCounts(all: List<LaundryRequest>): Map<LaundryStatus, Int> {
    val active = all.filter { laundryIsActive(it) }
    val counts = LinkedHashMap<LaundryStatus, Int>()
    LAUNDRY_ACTIVE_STATUSES.forEach { s -> counts[s] = active.count { it.status == s } }
    counts[LaundryStatus.DELIVERED] = all.count { it.status == LaundryStatus.DELIVERED }
    return counts
}

/** "3 active requests", "1 active request". */
fun laundryActiveSubtitle(activeCount: Int): String = "$activeCount active request${if (activeCount == 1) "" else "s"}"

/** "3 items" / "1 item". */
fun laundryItemCountText(n: Int): String = "$n item${if (n == 1) "" else "s"}"

/** "{items description} · {n} item(s)", or just the count text when there is no description. Null when the request has neither. */
fun laundryItemsLine(r: LaundryRequest): String? {
    val desc = r.itemsDescription
    return when {
        desc != null -> "$desc · ${laundryItemCountText(r.itemCount)}"
        else -> null
    }
}

// ── payment method ──

enum class LaundryPaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"), CARD("card", "Card"), ROOM_CHARGE("room_charge", "Charge to Room"),
    PAY_ON_DELIVERY("pay_on_delivery", "Pay on Delivery")
}

internal val DEFAULT_LAUNDRY_PAYMENT = LaundryPaymentMethod.ROOM_CHARGE

// ── new request form ──

/** What the person typed in the New Laundry Request sheet. */
data class LaundryRequestForm(
    val guestName: String = "",
    val roomNumber: String = "",
    val items: String = "",
    val itemCount: String = "1",
    val charge: String = "",
    val paymentMethod: LaundryPaymentMethod = DEFAULT_LAUNDRY_PAYMENT,
    val notes: String = ""
)

/** Guest name OR room number is required. */
fun laundryHasGuestInfo(form: LaundryRequestForm): Boolean = form.guestName.isNotBlank() || form.roomNumber.isNotBlank()

private fun cleanNumberText(text: String): String = text.trim().replace(",", "").replace("₦", "").trim()

/** Item count: a whole number of 1 or more; anything else falls back to 1. */
fun parseLaundryItemCount(text: String): Int {
    val d = cleanNumberText(text).toDoubleOrNull() ?: return 1
    if (d.isNaN() || d.isInfinite() || d < 1.0) return 1
    return if (d > Int.MAX_VALUE) Int.MAX_VALUE else d.toInt()
}

/** Charge when logging a request: zero or more; anything else falls back to 0. */
fun parseNewCharge(text: String): Double {
    val d = cleanNumberText(text).toDoubleOrNull() ?: return 0.0
    return if (d.isNaN() || d.isInfinite() || d < 0.0) 0.0 else d
}

/** Charge in the Update Charge dialog: null (= ignore) when it is not a number or is negative. */
fun parseChargeUpdate(text: String): Double? {
    val d = cleanNumberText(text).toDoubleOrNull() ?: return null
    return if (d.isNaN() || d.isInfinite() || d < 0.0) null else d
}

/** The charge as it is typed back into the dialog: "4000" or "4000.5". */
fun chargeFieldText(charge: Double): String =
    if (charge == Math.floor(charge) && charge < 1.0e15) charge.toLong().toString() else charge.toString()

private fun String.orNullIfBlank(): String? = trim().ifEmpty { null }

/** "Mr Okoro", else "Room 201", else "Guest" (used for the form, where one of the two is always present). */
fun guestOrRoomLabel(guestName: String?, roomNumber: String?): String =
    guestName?.trim()?.takeIf { it.isNotEmpty() }
        ?: roomNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { "Room $it" }
        ?: "Guest"

/** The exact `laundry_requests/{new}` document of section 2.5. Blank text fields are stored as null. */
fun buildCreatePayload(form: LaundryRequestForm, uid: String, valetName: String): Map<String, Any?> = mapOf(
    "guestName" to form.guestName.orNullIfBlank(),
    "roomNumber" to form.roomNumber.orNullIfBlank(),
    "itemsDescription" to form.items.orNullIfBlank(),
    "itemCount" to parseLaundryItemCount(form.itemCount),
    "charge" to parseNewCharge(form.charge),
    "paymentMethod" to form.paymentMethod.key,
    "paymentStatus" to PaymentStatus.UNPAID.key,
    "notes" to form.notes.orNullIfBlank(),
    "status" to LaundryStatus.RECEIVED.key,
    "laundryValetId" to uid,
    "laundryValetName" to valetName,
    "approvalStatus" to "pending",
    "approvedBy" to null,
    "approvedByName" to null,
    "approvedAt" to null,
    "rejectedReason" to null,
    "receivedAt" to LaundryServerTime,
    "collectedAt" to null,
    "deliveredAt" to null,
    "createdAt" to LaundryServerTime,
    "isDeleted" to false
)

// ── status, charge and payment updates ──

/** `Mark {next}`: status, `updatedAt`, `updatedBy`, plus `deliveredAt` when the next status is Delivered. */
fun buildAdvancePayload(next: LaundryStatus, uid: String): Map<String, Any?> {
    val fields = linkedMapOf<String, Any?>(
        "status" to next.key,
        "updatedAt" to LaundryServerTime,
        "updatedBy" to uid
    )
    if (next == LaundryStatus.DELIVERED) fields["deliveredAt"] = LaundryServerTime
    return fields
}

fun buildChargePayload(charge: Double): Map<String, Any?> = mapOf("charge" to charge, "updatedAt" to LaundryServerTime)

fun togglePayment(current: PaymentStatus): PaymentStatus =
    if (current == PaymentStatus.PAID) PaymentStatus.UNPAID else PaymentStatus.PAID

fun buildPaidPayload(current: PaymentStatus): Map<String, Any?> =
    mapOf("paymentStatus" to togglePayment(current).key, "updatedAt" to LaundryServerTime)
