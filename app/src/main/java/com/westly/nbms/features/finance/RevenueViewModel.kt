package com.westly.nbms.features.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal const val MSG_REVENUE_LOAD_FAILED = "We couldn't load revenue data."

/** What the Revenue page is showing. */
internal sealed interface RevenueUiState {
    data object Loading : RevenueUiState
    data class Error(val message: String) : RevenueUiState
    data class Ready(val summary: RevenueSummary) : RevenueUiState
}

/** Turns the ledger's answer into a page state. Pure, so it is unit-tested without Android. */
internal fun revenueStateOf(resource: Resource<List<RevenueTransaction>>, today: LocalDate, zone: ZoneId): RevenueUiState =
    when (resource) {
        is Resource.Loading -> RevenueUiState.Loading
        is Resource.Error -> RevenueUiState.Error(MSG_REVENUE_LOAD_FAILED)
        is Resource.Success -> RevenueUiState.Ready(buildRevenueSummary(resource.data, today, zone))
    }

/** The business time zone, or Africa/Lagos when the stored name is not a valid zone. */
internal fun businessZoneOf(timezone: String?): ZoneId =
    try {
        ZoneId.of(timezone ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }

/** Reads the page's transactions only through [RevenueLedger.observe]. */
@HiltViewModel
class RevenueViewModel @Inject constructor(
    private val ledger: RevenueLedger,
    private val session: SessionManager,
    private val navigator: ShellNavigator
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val state: StateFlow<RevenueUiState> = retryTick
        .flatMapLatest { ledger.observe() }
        .map { resource ->
            val zone = businessZoneOf((session.state.value as? SessionState.SignedIn)?.business?.timezone)
            revenueStateOf(resource, LocalDate.now(zone), zone)
        }
        .catch { emit(RevenueUiState.Error(MSG_REVENUE_LOAD_FAILED)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RevenueUiState.Loading)

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** The pending-approvals button. The Approvals page arrives with Phase 16B; until then the shell sends the person to the Dashboard. */
    fun openApprovals() {
        navigator.open("approvals")
    }
}
