package com.westly.nbms.features.reports

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
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

internal const val MSG_EXPENSES_LOAD_FAILED = "We couldn't load expenses."

/**
 * The `expenses/{id}` document as the Expenses page reads it. It is the same shape as [Expense], but `isDeleted` carries
 * an explicit Firestore name so the stored `isDeleted` field is really read (a Kotlin `isDeleted` property would otherwise
 * be looked up as `deleted`). It is turned into an [Expense] straight away.
 */
internal data class ExpenseDoc(
    @DocumentId val id: String = "",
    val title: String = "",
    val amount: Double = 0.0,
    val category: String = "other",
    val date: Timestamp? = null,
    val description: String? = null,
    val paymentMethod: String = "cash",
    val recordedBy: String? = null,
    val recordedByName: String = "",
    val createdAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
) {
    fun toExpense(): Expense = Expense(
        id = id, title = title, amount = amount, category = category, date = date, description = description,
        paymentMethod = paymentMethod, recordedBy = recordedBy, recordedByName = recordedByName,
        createdAt = createdAt, isDeleted = isDeleted
    )
}

/** The two controls above the list. [month] is "yyyy-MM", or empty for every month. */
internal data class ExpensesFilters(val month: String, val search: String)

/** One of the cards above the list: a category and its total in the rows that are showing. */
internal data class ExpenseCategoryCard(val category: ExpenseCategory, val amount: Double)

/** What the Expenses page is showing. [Ready.total] and [Ready.cards] are worked out from the FILTERED rows only. */
internal sealed interface ExpensesView {
    data object Loading : ExpensesView
    data class Error(val message: String) : ExpensesView
    data class Ready(val rows: List<Expense>, val total: Double, val cards: List<ExpenseCategoryCard>) : ExpensesView
}

// ── pure helpers (unit-tested without Android) ──

/** The business time zone, or Africa/Lagos when the stored name is missing or not a real zone. */
internal fun expensesZoneOf(timezone: String?): ZoneId =
    try {
        ZoneId.of(timezone ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }

/** "2026-10" for the month that contains [now] in [zone]. */
internal fun expensesCurrentMonth(zone: ZoneId, now: Instant = Instant.now()): String =
    YearMonth.from(now.atZone(zone)).toString()

/** "2026-10" -> "October 2026"; anything that is not a month is returned unchanged. */
internal fun expensesMonthLabel(month: String): String =
    try {
        val ym = YearMonth.parse(month)
        val name = ym.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        "$name ${ym.year}"
    } catch (e: Exception) {
        month
    }

/** The month the person picked, or null (every month) when it is empty or not a month. */
internal fun expensesParseMonth(month: String): YearMonth? {
    val clean = month.trim()
    if (clean.isEmpty()) return null
    return try {
        YearMonth.parse(clean)
    } catch (e: Exception) {
        null
    }
}

/** "food_beverage" -> "Food Beverage": underscores become spaces and every word is capitalised. A blank value is "—". */
internal fun expensesWords(raw: String?): String {
    val clean = raw?.trim().orEmpty()
    if (clean.isEmpty()) return "—"
    return clean.replace('_', ' ').split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}

/** The four biggest categories of [rows], biggest first (the cards above the list). */
internal fun expenseTopCategories(rows: List<Expense>): List<ExpenseCategoryCard> =
    ExpenseRules.byCategory(rows).take(4).map { (category, amount) -> ExpenseCategoryCard(category, amount) }

internal fun expensesViewOf(
    resource: Resource<List<ExpenseDoc>>,
    filters: ExpensesFilters,
    zone: ZoneId
): ExpensesView = when (resource) {
    is Resource.Loading -> ExpensesView.Loading
    is Resource.Error -> ExpensesView.Error(MSG_EXPENSES_LOAD_FAILED)
    is Resource.Success -> {
        val rows = ExpenseRules.filtered(resource.data.map { it.toExpense() }, expensesParseMonth(filters.month), filters.search, zone)
        ExpensesView.Ready(rows = rows, total = ExpenseRules.total(rows), cards = expenseTopCategories(rows))
    }
}

/** Reads `expenses` live and records new ones. */
@HiltViewModel
class ExpensesViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val zone: ZoneId = expensesZoneOf((session.state.value as? SessionState.SignedIn)?.business?.timezone)

    private val _filters = MutableStateFlow(ExpensesFilters(month = expensesCurrentMonth(zone), search = ""))
    internal val filters: StateFlow<ExpensesFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    private val _saving = MutableStateFlow(false)

    /** True from the tap on Save until the database answers. A second tap in that time does nothing. */
    internal val saving: StateFlow<Boolean> = _saving.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<ExpenseDoc>>> = retryTick
        .flatMapLatest { firestore.observeList("expenses", ExpenseDoc::class.java) }
        .catch { emit(Resource.Error(MSG_EXPENSES_LOAD_FAILED, it)) }

    internal val view: StateFlow<ExpensesView> = combine(live, _filters) { resource, filters ->
        expensesViewOf(resource, filters, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesView.Loading)

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
     * Records a new expense. [onSaved] runs after a successful save so the dialog can close.
     * The guard is set before anything suspends and is always cleared again.
     */
    internal fun save(form: RecordExpenseForm, signedIn: SessionState.SignedIn, onSaved: () -> Unit) {
        if (!_saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val errors = validateRecordExpenseForm(form)
                val amount = parseExpenseAmount(form.amountText)
                if (errors.any || amount == null) {
                    toast.show(
                        message = errors.title ?: errors.amount ?: "Please check the form.",
                        type = ToastType.Error,
                        title = "Error"
                    )
                } else {
                    val user = signedIn.user
                    val title = form.title.trim()
                    val businessZone = expensesZoneOf(signedIn.business.timezone)
                    val id = firestore.add("expenses", buildExpensePayload(form, user, businessZone))
                    guarded {
                        audit.log("expense_recorded", "expenses", id, null, mapOf("amount" to amount, "title" to title))
                    }
                    if (ExpenseRules.isLarge(amount)) {
                        guarded { notifier.notifyLargeExpense(title, amount, user.name) }
                    }
                    toast.show(
                        message = "${Format.currency(amount, signedIn.business.currencySymbol)} saved.",
                        type = ToastType.Success,
                        title = "Expense Recorded"
                    )
                    onSaved()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(
                    message = e.message ?: "Something went wrong. Please try again.",
                    type = ToastType.Error,
                    title = "Error"
                )
            } finally {
                _saving.value = false
            }
        }
    }

    /** The audit entry and the alert are extras: a failure there never turns a saved expense into an error. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
