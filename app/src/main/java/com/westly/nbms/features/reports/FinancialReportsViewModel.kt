package com.westly.nbms.features.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.finance.RevenueLedger
import com.westly.nbms.features.finance.RevenueTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

internal const val MSG_FINANCIAL_LOAD_FAILED = "We couldn't load financial data."

/** What the Financial Reports page is showing. */
internal sealed interface FinancialReportsView {
    data object Loading : FinancialReportsView
    data class Error(val message: String) : FinancialReportsView
    data class Ready(val report: FinancialReport) : FinancialReportsView
}

/** The business time zone, or Africa/Lagos when the stored name is missing or not a real zone. */
internal fun financialZoneOf(timezone: String?): ZoneId =
    try {
        ZoneId.of(timezone ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }

/** Turns the two live answers (revenue and expenses) into a page state. Pure, so it is unit-tested without Android. */
internal fun financialReportViewOf(
    revenue: Resource<List<RevenueTransaction>>,
    expenses: Resource<List<Expense>>,
    month: YearMonth,
    zone: ZoneId,
    businessName: String,
    currencySymbol: String
): FinancialReportsView {
    if (revenue is Resource.Error || expenses is Resource.Error) return FinancialReportsView.Error(MSG_FINANCIAL_LOAD_FAILED)
    val revenueRows = (revenue as? Resource.Success)?.data
    val expenseRows = (expenses as? Resource.Success)?.data
    if (revenueRows == null || expenseRows == null) return FinancialReportsView.Loading
    return FinancialReportsView.Ready(
        FinancialReportCalculator.build(revenueRows, expenseRows, month, zone, businessName, currencySymbol)
    )
}

/** Reads the revenue ledger and `expenses` live, works out the chosen month, and creates the PDF. */
@HiltViewModel
class FinancialReportsViewModel @Inject constructor(
    private val ledger: RevenueLedger,
    private val firestore: BusinessFirestore,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val _month = MutableStateFlow(
        YearMonth.now(financialZoneOf((session.state.value as? SessionState.SignedIn)?.business?.timezone))
    )
    internal val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    private val _exporting = MutableStateFlow(false)

    /** True while the PDF is being drawn. A second tap in that time does nothing. */
    internal val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val revenue: Flow<Resource<List<RevenueTransaction>>> = retryTick
        .flatMapLatest { ledger.observe() }
        .catch { emit(Resource.Error(MSG_FINANCIAL_LOAD_FAILED, it)) }

    /**
     * Expenses are read through [ExpenseDoc] (Phase 17A-2's reader, which maps the stored `isDeleted` field correctly)
     * and turned into [Expense] rows straight away; deleted rows are dropped.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val expenses: Flow<Resource<List<Expense>>> = retryTick
        .flatMapLatest { firestore.observeList("expenses", ExpenseDoc::class.java) }
        .map<Resource<List<ExpenseDoc>>, Resource<List<Expense>>> { resource ->
            when (resource) {
                is Resource.Success -> Resource.Success(resource.data.map { it.toExpense() }.filter { !it.isDeleted })
                is Resource.Loading -> Resource.Loading
                is Resource.Error -> resource
            }
        }
        .catch { emit(Resource.Error(MSG_FINANCIAL_LOAD_FAILED, it)) }

    internal val view: StateFlow<FinancialReportsView> = combine(revenue, expenses, _month) { revenueResource, expenseResource, month ->
        val signedIn = session.state.value as? SessionState.SignedIn
        financialReportViewOf(
            revenue = revenueResource,
            expenses = expenseResource,
            month = month,
            zone = financialZoneOf(signedIn?.business?.timezone),
            businessName = signedIn?.business?.name ?: "NBMS",
            currencySymbol = signedIn?.business?.currencySymbol ?: "₦"
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinancialReportsView.Loading)

    internal fun setMonth(month: YearMonth) {
        _month.value = month
    }

    internal fun retry() {
        retryTick.update { it + 1 }
    }

    /** Draws the PDF of the month on screen and opens the Android share sheet. A failure shows the "Couldn't create the PDF" toast. */
    internal fun exportPdf(context: Context, signedIn: SessionState.SignedIn) {
        val report = (view.value as? FinancialReportsView.Ready)?.report ?: return
        if (!_exporting.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val zoneId = financialZoneOf(signedIn.business.timezone)
                val zone = runCatching { TimeZone.of(zoneId.id) }.getOrElse { TimeZone.currentSystemDefault() }
                val generatedAt = Format.dateTime(Clock.System.now(), zone)
                val bytes = withContext(Dispatchers.Default) {
                    FinancialReportPdf.create(report, generatedAt, signedIn.user.name)
                }
                ShareFiles.shareBytes(context, financialReportFileName(report.month), bytes, "application/pdf").getOrThrow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = "Please try again.", type = ToastType.Error, title = "Couldn't create the PDF")
            } finally {
                _exporting.value = false
            }
        }
    }
}
