package com.westly.nbms.features.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.rbac.isPinEligible
import com.westly.nbms.features.staff.StaffAccountsApi
import com.westly.nbms.features.users.models.StaffUser
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

internal const val MSG_REQUIRED = "Name, email and password are required."
internal const val MSG_PASSWORD_SHORT = "Password must be at least 8 characters."
internal const val MSG_PIN_SHORT = "PIN must be at least 4 digits."
internal const val MSG_NO_ROLE = "Turn on at least one role in Roles & Permissions first."
internal const val MSG_GENERIC = "Something went wrong. Please try again."

/** The message to show for the "Create New User" form, or null when it may be sent. */
internal fun validateNewUser(name: String, email: String, password: String, role: Role?, pin: String): String? {
    if (name.isBlank() || email.isBlank() || password.isEmpty()) return MSG_REQUIRED
    if (password.length < 8) return MSG_PASSWORD_SHORT
    if (role == null) return MSG_NO_ROLE
    if (role.isPinEligible() && pin.isNotEmpty() && pin.length < 4) return MSG_PIN_SHORT
    return null
}

internal fun validateNewPassword(password: String): String? =
    if (password.length < 8) MSG_PASSWORD_SHORT else null

internal fun validateNewPin(pin: String): String? =
    if (pin.length < 4) MSG_PIN_SHORT else null

/** Keeps digits only, at most six (shared-device PIN fields). */
internal fun cleanPinInput(raw: String): String = raw.filter { it in '0'..'9' }.take(6)

/** Staff list order: A to Z by name. */
internal fun sortUsers(users: List<StaffUser>): List<StaffUser> =
    users.sortedBy { it.name.lowercase() }

// ---- ViewModel ------------------------------------------------------------------------------

enum class ResetKind { PASSWORD, PIN }

/** Which account a reset dialog is open for. */
data class ResetTarget(val user: StaffUser, val kind: ResetKind)

data class UsersUiState(
    val creating: Boolean = false,
    val resetting: Boolean = false,
    /** Ids of rows whose suspend/restore call is running. */
    val busyIds: Set<String> = emptySet()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsersViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val api: StaffAccountsApi,
    private val navigator: ShellNavigator,
    private val toast: ToastController
) : ViewModel() {

    private val _state = MutableStateFlow(UsersUiState())
    val state: StateFlow<UsersUiState> = _state.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    /** Live staff accounts (the read-only mirror), A to Z. */
    val users: StateFlow<Resource<List<StaffUser>>> = retryTick
        .flatMapLatest { firestore.observeList("users", StaffUser::class.java) }
        .map { r -> if (r is Resource.Success) Resource.Success(sortUsers(r.data)) else r }
        .catch { emit(Resource.Error("We couldn't load staff accounts.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    fun retry() {
        retryTick.update { it + 1 }
    }

    fun openRoles() = navigator.open("roles")

    /** Sends the new account to the server. [onSuccess] runs on success so the dialog can close. */
    fun createUser(
        name: String,
        email: String,
        phone: String,
        password: String,
        role: Role,
        pin: String,
        onSuccess: () -> Unit
    ) {
        if (_state.value.creating) return
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            val result = api.createUser(
                name = name.trim(),
                email = email.trim(),
                password = password,
                phone = phone.trim().ifEmpty { null },
                role = role,
                pin = if (role.isPinEligible() && pin.isNotEmpty()) pin else null
            )
            _state.update { it.copy(creating = false) }
            val failure = result.exceptionOrNull()
            if (failure == null) {
                toast.show("${name.trim()} can now log in.", ToastType.Success, "User Created")
                onSuccess()
            } else {
                toast.show(failure.message ?: MSG_GENERIC, ToastType.Error, "Failed to Create User")
            }
        }
    }

    /** Suspends an active account or restores a suspended one. */
    fun toggleStatus(user: StaffUser) {
        if (user.id in _state.value.busyIds) return
        val suspend = user.status == "active"
        _state.update { it.copy(busyIds = it.busyIds + user.id) }
        viewModelScope.launch {
            val failure = api.setStatus(user.id, if (suspend) "suspended" else "active").exceptionOrNull()
            _state.update { it.copy(busyIds = it.busyIds - user.id) }
            if (failure == null) {
                if (suspend) {
                    toast.show("${user.name} can no longer log in.", ToastType.Success, "User Suspended")
                } else {
                    toast.show("${user.name} can log in again.", ToastType.Success, "User Restored")
                }
            } else {
                toast.show(failure.message ?: MSG_GENERIC, ToastType.Error, "Failed to Update Status")
            }
        }
    }

    /** Sets a new password or PIN. [onSuccess] runs on success so the dialog can close. */
    fun reset(target: ResetTarget, value: String, onSuccess: () -> Unit) {
        if (_state.value.resetting) return
        _state.update { it.copy(resetting = true) }
        viewModelScope.launch {
            val name = target.user.name
            val result = when (target.kind) {
                ResetKind.PASSWORD -> api.resetPassword(target.user.id, value)
                ResetKind.PIN -> api.resetPin(target.user.id, value)
            }
            _state.update { it.copy(resetting = false) }
            val failure = result.exceptionOrNull()
            if (failure == null) {
                when (target.kind) {
                    ResetKind.PASSWORD -> toast.show("$name's password has been updated.", ToastType.Success, "Password Reset")
                    ResetKind.PIN -> toast.show("$name's PIN has been updated.", ToastType.Success, "PIN Reset")
                }
                onSuccess()
            } else {
                toast.show(failure.message ?: MSG_GENERIC, ToastType.Error, "Reset Failed")
            }
        }
    }
}
