package com.westly.nbms.features.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Rbac
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.users.models.StaffUser
import com.westly.nbms.features.users.models.roleOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

/** How many ACTIVE staff accounts hold each role. Suspended accounts and unknown roles are not counted. */
internal fun activeCountsByRole(users: List<StaffUser>): Map<Role, Int> =
    users.filter { it.status == "active" }
        .mapNotNull { it.roleOrNull() }
        .groupingBy { it }
        .eachCount()

/** A role that is on cannot be turned off while active staff still hold it. Turning a role on is always allowed. */
internal fun canToggleRole(isEnabled: Boolean, activeCount: Int): Boolean = !(isEnabled && activeCount > 0)

internal fun roleBlockedCaption(activeCount: Int): String =
    "$activeCount active user(s) — suspend or move them first"

/** The full set of roles after switching [role] on or off. Super Admin is never part of the set. */
internal fun toggledRoles(current: Set<Role>, role: Role, enable: Boolean): Set<Role> {
    if (role == Role.SUPER_ADMIN) return current - Role.SUPER_ADMIN
    val next = if (enable) current + role else current - role
    return next - Role.SUPER_ADMIN
}

/** One-line description shown under each role in "Roles used by your business". */
internal fun roleDescription(role: Role): String = when (role) {
    Role.SUPER_ADMIN -> "Owner of the business with full access."
    Role.MANAGER -> "Oversees bookings, approvals, reports and staff performance."
    Role.RECEPTIONIST -> "Front desk: check-in, check-out, bookings and payments."
    Role.ACCOUNTANT -> "Expenses, revenue, payments and financial reports."
    Role.STAFF -> "Makes sales at the point of sale and sees their own sales."
    Role.WAITER -> "Takes restaurant orders and sees their own orders."
    Role.HOUSEKEEPING -> "Cleans rooms, reports damage and handles lost and found."
    Role.BAR_ATTENDANT -> "Records bar sales and views the drinks menu and bar stock."
    Role.LAUNDRY_VALET -> "Handles laundry requests and updates their status."
    Role.OPERATIONS_MANAGER -> "Runs daily operations: tasks, shifts, housekeeping and reports."
    Role.MAINTENANCE_TECHNICIAN -> "Handles maintenance jobs and sees their own tasks and shifts."
    Role.SECURITY_GUARD -> "Sees their own tasks and shifts."
    Role.DRIVER -> "Sees their own tasks and shifts."
    Role.RESTAURANT_ATTENDANT -> "Restaurant floor team; sees their own tasks and shifts."
    Role.KITCHEN_STAFF -> "Kitchen team; sees their own tasks and shifts."
    Role.GYM_STAFF -> "Manages gym members, check-ins and attendance."
}

// ---- ViewModel ------------------------------------------------------------------------------

data class RolesUiState(
    /** The role whose switch is being saved (all switches wait while this is set). */
    val busyRole: Role? = null,
    /** Switch positions chosen by the person that the live business data has not caught up with yet. */
    val pending: Map<Role, Boolean> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RolesViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val api: BusinessRolesApi,
    private val toast: ToastController
) : ViewModel() {

    private val _state = MutableStateFlow(RolesUiState())
    val state: StateFlow<RolesUiState> = _state.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    /** Live staff list, used only to count active members per role. */
    val users: StateFlow<Resource<List<StaffUser>>> = retryTick
        .flatMapLatest { firestore.observeList("users", StaffUser::class.java) }
        .catch { emit(Resource.Error("We couldn't load staff accounts.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    val activeCounts: StateFlow<Map<Role, Int>> = users
        .map { r -> if (r is Resource.Success) activeCountsByRole(r.data) else emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Called whenever the live business data changes: forget switch positions it now agrees with. */
    fun syncWith(enabled: Set<Role>) {
        _state.update { s ->
            val still = s.pending.filter { (role, wanted) -> (role in enabled) != wanted }
            if (still.size == s.pending.size) s else s.copy(pending = still)
        }
    }

    /** What the switch for [role] should show right now. */
    fun effectiveEnabled(role: Role, enabled: Set<Role>): Boolean = _state.value.pending[role] ?: (role in enabled)

    fun setRole(role: Role, enable: Boolean, enabled: Set<Role>) {
        if (_state.value.busyRole != null || role == Role.SUPER_ADMIN) return
        val currentSet = Rbac.assignableRoles.filter { effectiveEnabled(it, enabled) }.toSet()
        val next = toggledRoles(currentSet, role, enable)
        _state.update { it.copy(busyRole = role, pending = it.pending + (role to enable)) }
        viewModelScope.launch {
            val failure = api.setRoles(next).exceptionOrNull()
            if (failure == null) {
                _state.update { it.copy(busyRole = null) }
                toast.show("Roles updated", ToastType.Success)
            } else {
                _state.update { it.copy(busyRole = null, pending = it.pending - role) }
                toast.show(failure.message ?: "Something went wrong. Please try again.", ToastType.Error, "Couldn't update roles")
            }
        }
    }
}
