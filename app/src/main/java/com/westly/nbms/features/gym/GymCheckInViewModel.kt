package com.westly.nbms.features.gym

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

internal const val MSG_GYM_DATA_LOAD_FAILED = "We couldn't load gym data. Please check your connection."

/** Most members shown under the search box. */
internal const val MAX_SEARCH_RESULTS = 8

private const val CLOCK_TICK_MS = 60_000L

/** One search result: the member, what the person should see as their status, and whether they are in the gym right now. */
internal data class MemberMatch(val member: GymMember, val status: MembershipStatus, val inGym: Boolean)

/** Everything the Check-In/Out page shows. [failed] is true when either live query failed. */
internal data class CheckInUi(
    val failed: Boolean = false,
    val membersLoading: Boolean = true,
    val visitsLoading: Boolean = true,
    /** True while the search box is not blank. */
    val searching: Boolean = false,
    val matches: List<MemberMatch> = emptyList(),
    /** Open visits (not checked out), newest first. */
    val inGym: List<GymVisit> = emptyList(),
    /** Today's visits (business time zone), newest first. */
    val today: List<GymVisit> = emptyList(),
    val timezone: String = "Africa/Lagos"
)

// ── pure helpers (unit-tested without Android) ──

private fun <T> Resource<List<T>>.dataOrEmpty(): List<T> = if (this is Resource.Success) data else emptyList()

/** Newest check-in first. A visit whose server time has not arrived yet (null) is the newest of all. */
internal fun visitNewestFirst(): Comparator<GymVisit> =
    compareByDescending<GymVisit> { it.checkInAt.toGymInstant()?.toEpochMilli() ?: Long.MAX_VALUE }

/** Members whose name, phone or room contains the text (trimmed, any case), not removed, by name, at most [limit]. A blank text finds nobody. */
internal fun searchMembers(
    members: List<GymMember>,
    query: String,
    openMemberIds: Set<String>,
    now: Instant,
    limit: Int = MAX_SEARCH_RESULTS
): List<MemberMatch> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return members
        .filter { !it.isDeleted }
        .filter { memberMatchesText(it, needle) }
        .sortedBy { it.name.lowercase() }
        .take(limit)
        .map { m ->
            MemberMatch(
                member = m,
                status = GymLogic.effectiveStatus(m, now),
                inGym = !m.activeVisitId.isNullOrBlank() || m.id in openMemberIds
            )
        }
}

/** Name, phone or room number contains [needle] (already trimmed), ignoring case. */
internal fun memberMatchesText(member: GymMember, needle: String): Boolean =
    member.name.contains(needle, ignoreCase = true) ||
        member.phone?.contains(needle, ignoreCase = true) == true ||
        member.roomNumber?.contains(needle, ignoreCase = true) == true

/** Visits with no check-out time, not deleted, newest first. */
internal fun openVisitsOf(visits: List<GymVisit>): List<GymVisit> =
    visits.filter { it.checkOutAt == null && !it.isDeleted }.sortedWith(visitNewestFirst())

/** Visits of one day (`dateKey`), not deleted, newest first. */
internal fun visitsOfDay(visits: List<GymVisit>, dateKey: String): List<GymVisit> =
    visits.filter { it.dateKey == dateKey && !it.isDeleted }.sortedWith(visitNewestFirst())

internal fun buildCheckInUi(
    members: Resource<List<GymMember>>,
    visits: Resource<List<GymVisit>>,
    query: String,
    now: Instant,
    timezone: String
): CheckInUi {
    val memberList = members.dataOrEmpty()
    val visitList = visits.dataOrEmpty()
    val open = openVisitsOf(visitList)
    return CheckInUi(
        failed = members is Resource.Error || visits is Resource.Error,
        membersLoading = members is Resource.Loading,
        visitsLoading = visits is Resource.Loading,
        searching = query.isNotBlank(),
        matches = searchMembers(memberList, query, open.map { it.memberId }.toSet(), now),
        inGym = open,
        today = visitsOfDay(visitList, GymLogic.dateKey(now, timezone)),
        timezone = timezone
    )
}

/** Searches members, checks them in and out. Each button blocks a second tap until the database answers. */
@HiltViewModel
class GymCheckInViewModel @Inject constructor(
    private val members: GymMembersRepository,
    private val visits: GymVisitsRepository,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val _query = MutableStateFlow("")
    internal val query: StateFlow<String> = _query.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the buttons that are waiting for the database: "in:{memberId}" and "out:{visitId}". */
    internal val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** The synchronous double-tap guard (a StateFlow update alone would let two quick taps through). */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val clock: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(CLOCK_TICK_MS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveMembers: Flow<Resource<List<GymMember>>> = retryTick
        .flatMapLatest { members.observe() }
        .catch { emit(Resource.Error(MSG_GYM_DATA_LOAD_FAILED, it)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveVisits: Flow<Resource<List<GymVisit>>> = retryTick
        .flatMapLatest { visits.observeVisits() }
        .catch { emit(Resource.Error(MSG_GYM_DATA_LOAD_FAILED, it)) }

    internal val ui: StateFlow<CheckInUi> = combine(liveMembers, liveVisits, _query, clock) { m, v, q, now ->
        buildCheckInUi(m, v, q, now, timezone())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckInUi())

    private fun timezone(): String =
        (session.state.value as? SessionState.SignedIn)?.business?.timezone ?: "Africa/Lagos"

    fun setQuery(text: String) {
        _query.value = text
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Checks the member in. The search clears after a success. */
    fun checkIn(member: GymMember) {
        val key = "in:${member.id}"
        if (!inFlight.add(key)) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                val outcome = visits.checkIn(member)
                toast.show(message = "${outcome.memberName} is now in the gym.", type = ToastType.Success, title = "Checked In")
                _query.value = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: MSG_GYM_GENERIC, type = ToastType.Error, title = "Check-In Failed")
            } finally {
                inFlight.remove(key)
                _busy.update { it - key }
            }
        }
    }

    /** Checks the person of the open visit out. */
    fun checkOut(visit: GymVisit) {
        val key = "out:${visit.id}"
        if (!inFlight.add(key)) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                val outcome = visits.checkOut(visit)
                toast.show(message = "${outcome.memberName} has left the gym.", type = ToastType.Success, title = "Checked Out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: MSG_GYM_GENERIC, type = ToastType.Error, title = "Check-Out Failed")
            } finally {
                inFlight.remove(key)
                _busy.update { it - key }
            }
        }
    }
}
