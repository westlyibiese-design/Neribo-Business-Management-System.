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
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.finance.RevenueLedger
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.rooms.Room
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

internal const val MSG_ANNUAL_LOAD_FAILED = "We couldn't load report data."

/** What the Annual Reports page is showing. */
internal sealed interface AnnualReportView {
    data object Loading : AnnualReportView
    data class Error(val message: String) : AnnualReportView
    data class Ready(val report: AnnualReport) : AnnualReportView
}

/** The business time zone, or Africa/Lagos when the stored name is missing or not a real zone. */
internal fun annualZoneOf(timezone: String?): ZoneId =
    try {
        ZoneId.of(timezone ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }

/**
 * Turns the four live answers (revenue, expenses, bookings, rooms) into a page state. Any error is an error; anything
 * still loading is loading; otherwise the report is worked out. Pure, so it is unit-tested without Android.
 */
internal fun annualReportViewOf(
    revenue: Resource<List<RevenueTransaction>>,
    expenses: Resource<List<Expense>>,
    bookings: Resource<List<Booking>>,
    rooms: Resource<List<Room>>,
    current: YearMonth,
    zone: ZoneId
): AnnualReportView {
    if (revenue is Resource.Error || expenses is Resource.Error || bookings is Resource.Error || rooms is Resource.Error) {
        return AnnualReportView.Error(MSG_ANNUAL_LOAD_FAILED)
    }
    val revenueRows = (revenue as? Resource.Success)?.data
    val expenseRows = (expenses as? Resource.Success)?.data
    val bookingRows = (bookings as? Resource.Success)?.data
    val roomRows = (rooms as? Resource.Success)?.data
    if (revenueRows == null || expenseRows == null || bookingRows == null || roomRows == null) return AnnualReportView.Loading
    return AnnualReportView.Ready(
        AnnualReportCalculator.build(revenueRows, expenseRows, bookingRows, roomRows, current, zone)
    )
}

/** Reads the revenue ledger, `expenses`, `bookings` and `rooms` live, works out the year so far, and shares the CSV. */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val ledger: RevenueLedger,
    private val firestore: BusinessFirestore,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val revenue: Flow<Resource<List<RevenueTransaction>>> = retryTick
        .flatMapLatest { ledger.observe() }
        .catch { emit(Resource.Error(MSG_ANNUAL_LOAD_FAILED, it)) }

    /** Expenses are read through [ExpenseDoc] (it maps the stored `isDeleted` field correctly) and turned into [Expense] rows. */
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
        .catch { emit(Resource.Error(MSG_ANNUAL_LOAD_FAILED, it)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val bookings: Flow<Resource<List<Booking>>> = retryTick
        .flatMapLatest { firestore.observeList("bookings", Booking::class.java) }
        .catch { emit(Resource.Error(MSG_ANNUAL_LOAD_FAILED, it)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rooms: Flow<Resource<List<Room>>> = retryTick
        .flatMapLatest { firestore.observeList("rooms", Room::class.java) }
        .catch { emit(Resource.Error(MSG_ANNUAL_LOAD_FAILED, it)) }

    internal val view: StateFlow<AnnualReportView> = combine(revenue, expenses, bookings, rooms) { r, e, b, rm ->
        val zone = annualZoneOf((session.state.value as? SessionState.SignedIn)?.business?.timezone)
        annualReportViewOf(r, e, b, rm, YearMonth.now(zone), zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnualReportView.Loading)

    internal fun retry() {
        retryTick.update { it + 1 }
    }

    /** Opens the Android share sheet with `annual-report-{yyyy}.csv`. A failure shows a toast. */
    internal fun exportCsv(context: Context) {
        val report = (view.value as? AnnualReportView.Ready)?.report ?: return
        val result = ShareFiles.shareText(context, annualReportCsvFileName(report.year), buildAnnualReportCsv(report))
        if (result.isFailure) {
            toast.show(message = "Please try again.", type = ToastType.Error, title = "Couldn't export the CSV")
        }
    }
}
