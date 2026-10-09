package com.westly.nbms.features.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

internal const val MSG_PAYMENTS_LOAD_FAILED = "We couldn't load payments."

/** How long the "Ending session for security…" screen stays up before a shared-device (PIN) session signs out. */
internal const val PAYMENTS_END_SESSION_DELAY_MS = 2_500L

/** The `payments/{id}` document as the Payments page reads it. Only this page uses it. */
internal data class PaymentDoc(
    @DocumentId val id: String = "",
    val type: String? = null,
    val guestName: String? = null,
    val amount: Double = 0.0,
    val paymentMethod: String? = null,
    val bookingId: String? = null,
    val notes: String? = null,
    val createdAt: Timestamp? = null,
    val recordedBy: String? = null,
    val recordedByName: String? = null,
    val approvalStatus: String? = null,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Timestamp? = null,
    val rejectedReason: String? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** The two controls above the list. [month] is "yyyy-MM", or empty for every month. */
internal data class PaymentsFilters(val month: String, val search: String)

internal data class PaymentTotals(val approved: Double, val pending: Double)

/** What the Payments page is showing. */
internal sealed interface PaymentsView {
    data object Loading : PaymentsView
    data class Error(val message: String) : PaymentsView
    data class Ready(val rows: List<PaymentDoc>, val totals: PaymentTotals) : PaymentsView
}

/** How an amount is drawn: approved green bold, rejected red with a line through it, pending muted. */
internal enum class PaymentAmountStyle(val bold: Boolean, val strikethrough: Boolean) {
    APPROVED(bold = true, strikethrough = false),
    REJECTED(bold = false, strikethrough = true),
    PENDING(bold = false, strikethrough = false)
}

// ── pure helpers (unit-tested without Android) ──

/** "approved" or "rejected"; anything else, including a missing value, is "pending". */
internal fun paymentStatusKey(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "approved" -> "approved"
    "rejected" -> "rejected"
    else -> "pending"
}

internal fun paymentAmountStyle(statusKey: String): PaymentAmountStyle = when (paymentStatusKey(statusKey)) {
    "approved" -> PaymentAmountStyle.APPROVED
    "rejected" -> PaymentAmountStyle.REJECTED
    else -> PaymentAmountStyle.PENDING
}

/** Only the Super Admin and the Receptionist may record a payment; the database allows nobody else to create one. */
internal fun paymentsCanRecord(role: Role): Boolean = role == Role.SUPER_ADMIN || role == Role.RECEPTIONIST

/** The business time zone, or Africa/Lagos when the stored name is missing or not a real zone. */
internal fun paymentsZoneOf(timezone: String?): ZoneId =
    try {
        ZoneId.of(timezone ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }

internal fun paymentInstant(doc: PaymentDoc): Instant? =
    doc.createdAt?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }

/** "2026-10" for the month that contains [now] in [zone]. */
internal fun paymentsCurrentMonth(zone: ZoneId, now: Instant = Instant.now()): String =
    YearMonth.from(now.atZone(zone)).toString()

/** "2026-10" -> "October 2026"; anything that is not a month is returned unchanged. */
internal fun paymentsMonthLabel(month: String): String =
    try {
        val ym = YearMonth.parse(month)
        val name = ym.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        "$name ${ym.year}"
    } catch (e: Exception) {
        month
    }

/** "room_payment" -> "Room Payment"; the page's five types, plus "stay_extension" -> "Stay Extension". */
internal fun paymentsTypeLabel(raw: String?): String = when (raw?.trim()) {
    "room_payment" -> "Room Payment"
    "walk_in_payment" -> "Walk-In"
    "deposit" -> "Deposit"
    "refund" -> "Refund"
    "other" -> "Other"
    "stay_extension" -> "Stay Extension"
    null, "" -> "—"
    else -> paymentsWords(raw)
}

/** "credit_card" -> "Credit Card": underscores become spaces and every word is capitalised. */
internal fun paymentsMethodLabel(raw: String?): String {
    val clean = raw?.trim().orEmpty()
    return if (clean.isEmpty()) "—" else paymentsWords(clean)
}

private fun paymentsWords(raw: String): String =
    raw.trim().replace('_', ' ').split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

/**
 * The rows the page shows: not deleted, in the chosen month (an empty [month] keeps every month), matching the search
 * (guest name or the raw type string, ignoring case), newest first. A payment that has no date yet (a fresh write that the
 * server has not stamped) is kept in any month and shown first.
 */
internal fun filterPayments(all: List<PaymentDoc>, month: String, search: String, zone: ZoneId): List<PaymentDoc> {
    val wantedMonth = month.trim()
    val q = search.trim().lowercase()
    return all.asSequence()
        .filter { !it.isDeleted }
        .filter { doc ->
            if (wantedMonth.isEmpty()) true
            else paymentInstant(doc)?.let { YearMonth.from(it.atZone(zone)).toString() == wantedMonth } ?: true
        }
        .filter { doc ->
            q.isEmpty() ||
                doc.guestName.orEmpty().lowercase().contains(q) ||
                doc.type.orEmpty().lowercase().contains(q)
        }
        .sortedByDescending { paymentInstant(it) ?: Instant.MAX }
        .toList()
}

/** Approved and pending money over [rows] (callers pass the FILTERED rows). Rejected money counts in neither. */
internal fun paymentTotals(rows: List<PaymentDoc>): PaymentTotals {
    var approved = 0.0
    var pending = 0.0
    rows.forEach {
        when (paymentStatusKey(it.approvalStatus)) {
            "approved" -> approved += it.amount
            "pending" -> pending += it.amount
        }
    }
    return PaymentTotals(approved = approved, pending = pending)
}

internal fun paymentsViewOf(
    resource: Resource<List<PaymentDoc>>,
    filters: PaymentsFilters,
    zone: ZoneId
): PaymentsView = when (resource) {
    is Resource.Loading -> PaymentsView.Loading
    is Resource.Error -> PaymentsView.Error(MSG_PAYMENTS_LOAD_FAILED)
    is Resource.Success -> {
        val rows = filterPayments(resource.data, filters.month, filters.search, zone)
        PaymentsView.Ready(rows, paymentTotals(rows))
    }
}

/** Reads `payments` live and records new ones. Approving and rejecting belong to the Approvals page, not here. */
@HiltViewModel
class PaymentsViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val zone: ZoneId = paymentsZoneOf((session.state.value as? SessionState.SignedIn)?.business?.timezone)

    private val _filters = MutableStateFlow(PaymentsFilters(month = paymentsCurrentMonth(zone), search = ""))
    internal val filters: StateFlow<PaymentsFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    private val _saving = MutableStateFlow(false)

    /** True from the tap on Save until the database answers. A second tap in that time does nothing. */
    internal val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _endingSession = MutableStateFlow(false)

    /** True while the "Ending session for security…" screen is up (shared-device PIN sessions only). */
    internal val endingSession: StateFlow<Boolean> = _endingSession.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<PaymentDoc>>> = retryTick
        .flatMapLatest { firestore.observeList("payments", PaymentDoc::class.java) }
        .catch { emit(Resource.Error(MSG_PAYMENTS_LOAD_FAILED, it)) }

    internal val view: StateFlow<PaymentsView> = combine(live, _filters) { resource, filters ->
        paymentsViewOf(resource, filters, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentsView.Loading)

    internal fun setMonth(month: String) {
        _filters.update { it.copy(month = month.trim()) }
    }

    internal fun setSearch(search: String) {
        _filters.update { it.copy(search = search) }
    }

    internal fun retry() {
        retryTick.update { it + 1 }
    }

    /**
     * Records a new payment. It is always saved as `pending`. [onSaved] runs after a successful save so the dialog can close.
     * On a shared-device (PIN) session the session ends 2.5 seconds after a successful save.
     */
    internal fun save(form: RecordPaymentForm, signedIn: SessionState.SignedIn, onSaved: () -> Unit) {
        if (!_saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            val saved = try {
                val errors = validateRecordPaymentForm(form)
                val amount = parsePaymentAmount(form.amountText)
                if (errors.any || amount == null) {
                    toast.show(
                        message = errors.guestName ?: errors.amount ?: "Please check the form.",
                        type = ToastType.Error,
                        title = "Error"
                    )
                    false
                } else {
                    val user = signedIn.user
                    val guest = form.guestName.trim()
                    val notes = form.notes.trim().ifEmpty { null }
                    val id = firestore.add("payments", buildPaymentPayload(form, user))
                    guarded { audit.log("payment_recorded", "payments", id, null, mapOf("amount" to amount)) }
                    guarded {
                        when (paymentAlertFor(form.type)) {
                            PaymentAlert.REFUND_ISSUED -> notifier.notifyRefundIssued(amount, guest, user.name, notes)
                            PaymentAlert.PAYMENT_RECEIVED ->
                                notifier.notifyPaymentReceived(amount, form.method.replace('_', ' '), guest, user.name)
                        }
                    }
                    toast.show(
                        message = "Sent to the Accountant for approval.",
                        type = ToastType.Success,
                        title = "Payment Recorded"
                    )
                    onSaved()
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(
                    message = e.message ?: "Something went wrong. Please try again.",
                    type = ToastType.Error,
                    title = "Error"
                )
                false
            } finally {
                _saving.value = false
            }
            if (saved && shouldEndPinSession(signedIn.user)) {
                _endingSession.value = true
                delay(PAYMENTS_END_SESSION_DELAY_MS)
                guarded { session.signOut() }
            }
        }
    }

    /** The audit entry and the alert are extras: a failure there never turns a saved payment into an error. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
