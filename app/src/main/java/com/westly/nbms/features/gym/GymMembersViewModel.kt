package com.westly.nbms.features.gym

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Days left at or below which an active membership shows the orange "{n}d left" note. */
internal const val EXPIRY_WARNING_DAYS = 7

private const val CLOCK_TICK_MS = 60_000L

/** The two controls above the list. [status] null means "All Statuses"; it is matched against the EFFECTIVE status. */
internal data class MemberFilters(val search: String = "", val status: MembershipStatus? = null)

/** One row of the Members page, worked out at one moment. */
internal data class MemberRow(val member: GymMember, val status: MembershipStatus, val daysLeft: Int?)

internal sealed interface MembersView {
    data object Loading : MembersView
    data class Error(val message: String) : MembersView

    /** [total] counts every non-removed member (before the filters); [rows] is the filtered list; [all] is every non-removed member (the sheets look their member up here). */
    data class Ready(val total: Int, val rows: List<MemberRow>, val all: List<GymMember> = emptyList()) : MembersView
}

// ── pure helpers (unit-tested without Android) ──

/** Non-removed members whose name, phone or room contains the search text and whose effective status matches, sorted by name. */
internal fun filterMembers(members: List<GymMember>, filters: MemberFilters, now: Instant): List<MemberRow> {
    val needle = filters.search.trim()
    return members
        .filter { !it.isDeleted }
        .map { m ->
            MemberRow(
                member = m,
                status = GymLogic.effectiveStatus(m, now),
                daysLeft = GymLogic.daysUntilExpiry(m.endDate.toGymInstant(), now)
            )
        }
        .filter { filters.status == null || it.status == filters.status }
        .filter { needle.isEmpty() || memberMatchesText(it.member, needle) }
        .sortedBy { it.member.name.lowercase() }
}

/** "{n}d left" (or "expires today") for an ACTIVE row with [EXPIRY_WARNING_DAYS] days or fewer left; otherwise null. */
internal fun expiryNote(row: MemberRow): String? {
    val days = row.daysLeft ?: return null
    if (row.status != MembershipStatus.ACTIVE || days > EXPIRY_WARNING_DAYS) return null
    return if (days <= 0) "expires today" else "${days}d left"
}

/** Phone, else email, else "Room {n}", else "—" (the line under the member's name). */
internal fun memberSubline(member: GymMember): String {
    member.phone?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    member.email?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    member.roomNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { return "Room $it" }
    return "—"
}

internal fun membersViewOf(resource: Resource<List<GymMember>>, filters: MemberFilters, now: Instant): MembersView = when (resource) {
    is Resource.Loading -> MembersView.Loading
    is Resource.Error -> MembersView.Error(MSG_MEMBERS_LOAD_FAILED)
    is Resource.Success -> {
        val all = resource.data.filter { !it.isDeleted }
        MembersView.Ready(total = all.size, rows = filterMembers(all, filters, now), all = all)
    }
}

/** The package the Renew sheet starts on: the member's current package if it is still listed, else the first, else none. */
internal fun defaultRenewPackage(member: GymMember, packages: List<GymPackage>): GymPackage? =
    packages.firstOrNull { it.id == member.packageId } ?: packages.firstOrNull()

/** Reads members and packages live and runs every change on the Members page. */
@HiltViewModel
class GymMembersViewModel @Inject constructor(
    private val repository: GymMembersRepository,
    private val toast: ToastController
) : ViewModel() {

    private val _filters = MutableStateFlow(MemberFilters())
    internal val filters: StateFlow<MemberFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the actions waiting for the database: "register", "renew:{id}", "edit:{id}", "status:{id}", "remove:{id}". */
    internal val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val clock: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(CLOCK_TICK_MS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<GymMember>>> = retryTick
        .flatMapLatest { repository.observe() }
        .catch { emit(Resource.Error(MSG_MEMBERS_LOAD_FAILED, it)) }

    internal val view: StateFlow<MembersView> = combine(live, _filters, clock) { resource, filters, now ->
        membersViewOf(resource, filters, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembersView.Loading)

    /** The packages in `cms_content/gym` (empty while loading or when there are none). */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val packages: StateFlow<List<GymPackage>> = retryTick
        .flatMapLatest { repository.observePackages() }
        .catch { emit(Resource.Error(MSG_GYM_GENERIC, it)) }
        .map { resource -> if (resource is Resource.Success) resource.data else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearch(text: String) {
        _filters.update { it.copy(search = text) }
    }

    fun setStatus(status: MembershipStatus?) {
        _filters.update { it.copy(status = status) }
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Runs [work] once per [key]: a second call with the same key is ignored until the first finishes. */
    private fun run(key: String, failureTitle: String, work: suspend () -> Unit) {
        if (!inFlight.add(key)) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: MSG_GYM_GENERIC, type = ToastType.Error, title = failureTitle)
            } finally {
                inFlight.remove(key)
                _busy.update { it - key }
            }
        }
    }

    /** Registers a member. [onDone] runs after a success so the sheet can reset and close. */
    fun register(form: RegisterMemberForm, onDone: () -> Unit) {
        run("register", "Registration Failed") {
            repository.register(form, packages.value)
            toast.show(message = "${form.name.trim()} is now an active gym member.", type = ToastType.Success, title = "Member Registered")
            onDone()
        }
    }

    /** Renews with [pkg]. No package shows the "Select a package" toast and does nothing else. */
    fun renew(member: GymMember, pkg: GymPackage?, onDone: () -> Unit) {
        if (pkg == null) {
            toast.show(message = MSG_SELECT_PACKAGE, type = ToastType.Error)
            return
        }
        run("renew:${member.id}", "Renewal Failed") {
            val outcome = repository.renew(member.id, pkg)
            toast.show(message = "${outcome.name}'s membership has been extended.", type = ToastType.Success, title = "Membership Renewed")
            onDone()
        }
    }

    fun edit(member: GymMember, form: EditMemberForm, onDone: () -> Unit) {
        run("edit:${member.id}", "Update Failed") {
            repository.updateDetails(member.id, form)
            toast.show(message = "${form.name.trim()}'s details were saved.", type = ToastType.Success, title = "Member Updated")
            onDone()
        }
    }

    fun suspendMember(member: GymMember) {
        run("status:${member.id}", "Failed") {
            repository.setStatus(member, MembershipStatus.SUSPENDED)
            toast.show(message = "${member.name} can't check in until reactivated.", type = ToastType.Success, title = "Membership Suspended")
        }
    }

    fun reactivateMember(member: GymMember) {
        run("status:${member.id}", "Failed") {
            repository.setStatus(member, MembershipStatus.ACTIVE)
            toast.show(message = "${member.name} can check in again.", type = ToastType.Success, title = "Membership Reactivated")
        }
    }

    fun removeMember(member: GymMember) {
        run("remove:${member.id}", "Failed") {
            repository.softDelete(member)
            toast.show(message = "${member.name} was removed from the active members list.", type = ToastType.Success, title = "Member Removed")
        }
    }
}
