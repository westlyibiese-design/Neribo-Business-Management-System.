package com.westly.nbms.features.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Rbac
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.util.Validators
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row on the roles step. */
data class RoleOption(val role: Role, val description: String)

/** The roles step is shown in these groups. */
data class RoleGroup(val title: String, val options: List<RoleOption>)

object RegisterRoles {
    val groups: List<RoleGroup> = listOf(
        RoleGroup(
            "Management", listOf(
                RoleOption(Role.MANAGER, "Oversees daily operations, approvals and reports."),
                RoleOption(Role.OPERATIONS_MANAGER, "Runs tasks, shifts and housekeeping, maintenance and venues."),
                RoleOption(Role.ACCOUNTANT, "Records expenses and handles revenue, payments and reports.")
            )
        ),
        RoleGroup(
            "Front desk & rooms", listOf(
                RoleOption(Role.RECEPTIONIST, "Handles bookings, check-in, check-out and guest payments."),
                RoleOption(Role.HOUSEKEEPING, "Cleans rooms and reports damage or lost items."),
                RoleOption(Role.MAINTENANCE_TECHNICIAN, "Fixes maintenance requests and updates their status."),
                RoleOption(Role.SECURITY_GUARD, "Sees own tasks and shifts."),
                RoleOption(Role.DRIVER, "Sees own tasks and shifts.")
            )
        ),
        RoleGroup(
            "Restaurant", listOf(
                RoleOption(Role.WAITER, "Takes restaurant orders."),
                RoleOption(Role.RESTAURANT_ATTENDANT, "Supports the restaurant floor; sees own tasks and shifts."),
                RoleOption(Role.KITCHEN_STAFF, "Prepares orders; sees own tasks and shifts.")
            )
        ),
        RoleGroup(
            "Bar", listOf(RoleOption(Role.BAR_ATTENDANT, "Records bar sales and checks bar stock."))
        ),
        RoleGroup(
            "Laundry", listOf(RoleOption(Role.LAUNDRY_VALET, "Records laundry requests and updates their status."))
        ),
        RoleGroup(
            "Gym", listOf(RoleOption(Role.GYM_STAFF, "Manages gym members, memberships and check-ins."))
        ),
        RoleGroup(
            "Sales", listOf(RoleOption(Role.STAFF, "Staff (point of sale): records sales and sees own sales."))
        )
    )

    val defaultSelection: Set<Role> = setOf(Role.MANAGER, Role.RECEPTIONIST, Role.ACCOUNTANT)
}

/** Pure validation rules for the wizard, kept separate so they can be unit tested. */
object RegisterValidation {
    fun fullName(value: String): String? =
        if (value.trim().length in 2..120) null else "Enter your full name (2 to 120 characters)."

    fun email(value: String): String? = when {
        value.isBlank() -> "Enter your email address."
        !Validators.email(value) -> "Enter a valid email address."
        else -> null
    }

    fun phone(value: String): String? =
        if (value.isBlank() || Validators.phone(value)) null else "Enter a valid phone number."

    fun password(value: String): String? = Validators.password(value)

    fun confirmPassword(password: String, confirm: String): String? = when {
        confirm.isEmpty() -> "Confirm your password."
        confirm != password -> "Passwords do not match."
        else -> null
    }

    fun businessName(value: String): String? =
        if (value.trim().length in 2..120) null else "Business name must be 2 to 120 characters."
}

internal const val MSG_CODE_NOT_SENT = "We couldn't send the code. Please wait a minute and try again."
internal const val MSG_SIGNUP_BAD_CODE = "That code is wrong or has expired. Check it or request a new one."

data class RegisterUiState(
    val step: Int = 1,                       // 1 = Owner, 2 = Hotel, 3 = Roles, 4 = Verify email
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val password: String = "",               // never saved to SavedStateHandle
    val confirmPassword: String = "",        // never saved to SavedStateHandle
    val businessName: String = "",
    val selectedRoles: Set<Role> = RegisterRoles.defaultSelection,
    val fullNameError: String? = null,
    val emailError: String? = null,
    val phoneError: String? = null,
    val passwordError: String? = null,
    val confirmError: String? = null,
    val businessNameError: String? = null,
    val code: String = "",                   // the 6-digit email code, never saved to SavedStateHandle
    val codeError: String? = null,
    val resendSeconds: Int = 0,
    val serverError: String? = null,
    val submitting: Boolean = false,
    val signingIn: Boolean = false,
    /** Set when the business was created: the code to show on the success screen. */
    val createdBusinessCode: String? = null
)

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val api: RegistrationApi,
    private val recovery: RecoveryClient,
    private val sessionManager: SessionManager,
    private val toast: ToastController,
    private val saved: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    /** Kept only in memory so the success screen's Continue button can sign in. */
    private var signInEmail: String = ""
    private var signInPassword: String = ""

    /** The address the last code was sent to, and the countdown before another code may be requested. */
    private var codeSentTo: String = ""
    private var cooldownJob: Job? = null

    private fun restore(): RegisterUiState {
        val roleKeys = saved.get<ArrayList<String>>(K_ROLES)
        return RegisterUiState(
            step = saved.get<Int>(K_STEP) ?: 1,
            fullName = saved.get<String>(K_NAME).orEmpty(),
            email = saved.get<String>(K_EMAIL).orEmpty(),
            phone = saved.get<String>(K_PHONE).orEmpty(),
            businessName = saved.get<String>(K_BUSINESS).orEmpty(),
            selectedRoles = roleKeys?.mapNotNull { Role.fromKey(it) }?.toSet() ?: RegisterRoles.defaultSelection,
            createdBusinessCode = saved.get<String>(K_CODE)
        )
    }

    private fun persist(s: RegisterUiState) {
        saved[K_STEP] = s.step
        saved[K_NAME] = s.fullName
        saved[K_EMAIL] = s.email
        saved[K_PHONE] = s.phone
        saved[K_BUSINESS] = s.businessName
        saved[K_ROLES] = ArrayList(s.selectedRoles.map { it.key })
        saved[K_CODE] = s.createdBusinessCode
    }

    private fun change(block: (RegisterUiState) -> RegisterUiState) {
        _state.update { block(it).also(::persist) }
    }

    // ------------------------------------------------------------ field changes

    fun onFullName(v: String) = change { it.copy(fullName = v, fullNameError = null, serverError = null) }
    fun onEmail(v: String) = change { it.copy(email = v, emailError = null, serverError = null) }
    fun onPhone(v: String) = change { it.copy(phone = v, phoneError = null) }
    fun onPassword(v: String) = change { it.copy(password = v, passwordError = null) }
    fun onConfirmPassword(v: String) = change { it.copy(confirmPassword = v, confirmError = null) }
    fun onCode(v: String) = change {
        it.copy(code = v.filter { c -> c.isDigit() }.take(6), codeError = null, serverError = null)
    }

    fun onBusinessName(v: String) = change { it.copy(businessName = v, businessNameError = null, serverError = null) }

    fun onToggleRole(role: Role, checked: Boolean) = change {
        it.copy(selectedRoles = if (checked) it.selectedRoles + role else it.selectedRoles - role, serverError = null)
    }

    // ------------------------------------------------------------ navigation between steps

    fun next() {
        val s = _state.value
        when (s.step) {
            1 -> {
                val nameErr = RegisterValidation.fullName(s.fullName)
                val emailErr = RegisterValidation.email(s.email)
                val phoneErr = RegisterValidation.phone(s.phone)
                val pwErr = RegisterValidation.password(s.password)
                val confirmErr = RegisterValidation.confirmPassword(s.password, s.confirmPassword)
                if (listOf(nameErr, emailErr, phoneErr, pwErr, confirmErr).any { it != null }) {
                    change {
                        it.copy(
                            fullNameError = nameErr, emailError = emailErr, phoneError = phoneErr,
                            passwordError = pwErr, confirmError = confirmErr
                        )
                    }
                } else {
                    change { it.copy(step = 2, serverError = null) }
                }
            }
            2 -> {
                val err = RegisterValidation.businessName(s.businessName)
                if (err != null) change { it.copy(businessNameError = err) }
                else change { it.copy(step = 3, serverError = null) }
            }
        }
    }

    /** Returns true when the wizard handled the back press (moved to the previous step). */
    fun back(): Boolean {
        val s = _state.value
        if (s.createdBusinessCode != null || s.submitting) return true
        return if (s.step > 1) {
            change { it.copy(step = it.step - 1, code = "", codeError = null, serverError = null) }
            true
        } else false
    }

    // ------------------------------------------------------------ submit

    /** "Create my business" on step 3: emails the 6-digit code and opens the Verify email step. */
    fun submit() {
        val s = _state.value
        if (s.submitting || s.createdBusinessCode != null) return
        if (!earlierStepsAreValid(s)) return
        val email = s.email.trim().lowercase()
        // A code was just sent to this same address: do not send another one, just show the step again.
        if (s.resendSeconds > 0 && codeSentTo == email) {
            change { it.copy(step = 4, code = "", codeError = null, serverError = null) }
            return
        }
        sendCode(email, openStep4 = true)
    }

    /** "Resend code" on step 4. */
    fun resendCode() {
        val s = _state.value
        if (s.submitting || s.resendSeconds > 0) return
        sendCode(s.email.trim().lowercase(), openStep4 = false)
    }

    private fun sendCode(email: String, openStep4: Boolean) {
        change { it.copy(submitting = true, serverError = null) }
        viewModelScope.launch {
            try {
                recovery.sendSignupCode(email)
                codeSentTo = email
                change {
                    it.copy(
                        submitting = false,
                        step = 4,
                        code = if (openStep4) "" else it.code,
                        codeError = null,
                        serverError = null
                    )
                }
                startCooldown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RestException) {
                change { it.copy(submitting = false, serverError = MSG_CODE_NOT_SENT) }
            } catch (e: Exception) {
                change { it.copy(submitting = false, serverError = MSG_REGISTER_NETWORK) }
            }
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (left in RESEND_COOLDOWN_SECONDS downTo 1) {
                change { it.copy(resendSeconds = left) }
                delay(1_000)
            }
            change { it.copy(resendSeconds = 0) }
        }
    }

    /** Re-checks steps 1 and 2 (state may have been restored after the app was closed). Moves back to the broken step when not valid. */
    private fun earlierStepsAreValid(s: RegisterUiState): Boolean {
        val problems = listOf(
            RegisterValidation.fullName(s.fullName), RegisterValidation.email(s.email),
            RegisterValidation.phone(s.phone), RegisterValidation.password(s.password),
            RegisterValidation.confirmPassword(s.password, s.confirmPassword)
        )
        if (problems.any { it != null }) {
            change {
                it.copy(
                    step = 1,
                    fullNameError = problems[0], emailError = problems[1], phoneError = problems[2],
                    passwordError = problems[3], confirmError = problems[4],
                    serverError = "Please check your details and enter your password again."
                )
            }
            return false
        }
        val businessErr = RegisterValidation.businessName(s.businessName)
        if (businessErr != null) {
            change { it.copy(step = 2, businessNameError = businessErr) }
            return false
        }
        return true
    }

    /** "Verify and create my business" on step 4: checks the code, then creates the business. */
    fun verifyAndCreate() {
        val s = _state.value
        if (s.submitting || s.createdBusinessCode != null) return
        if (!earlierStepsAreValid(s)) return
        if (s.code.length != 6) {
            change { it.copy(codeError = "Enter the 6-digit code from your email.") }
            return
        }
        val email = s.email.trim().lowercase()
        change { it.copy(submitting = true, serverError = null, codeError = null) }
        viewModelScope.launch {
            val token = try {
                recovery.verifySignupCode(email, s.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidRecoveryCodeException) {
                change { it.copy(submitting = false, codeError = MSG_SIGNUP_BAD_CODE) }
                return@launch
            } catch (e: Exception) {
                change { it.copy(submitting = false, serverError = MSG_REGISTER_NETWORK) }
                return@launch
            }
            val request = CreateBusinessRequest(
                ownerName = s.fullName.trim(),
                email = email,
                password = s.password,
                phone = s.phone.trim().ifEmpty { null },
                businessName = s.businessName.trim(),
                businessType = "hotel",
                enabledRoles = Rbac.assignableRoles.filter { it in s.selectedRoles }.map { it.key },
                verificationToken = token
            )
            api.createBusiness(request).fold(
                onSuccess = { result ->
                    recovery.endSignupVerification()
                    cooldownJob?.cancel()
                    signInEmail = email
                    signInPassword = s.password
                    change {
                        it.copy(
                            submitting = false,
                            createdBusinessCode = result.businessCode,
                            password = "", confirmPassword = "", code = "", resendSeconds = 0
                        )
                    }
                },
                onFailure = { e ->
                    val message = e.message ?: MSG_REGISTER_GENERIC
                    val toStep = when (message) {
                        MSG_EMAIL_EXISTS -> 1
                        MSG_VERIFY_EXPIRED -> 3
                        else -> 4
                    }
                    if (toStep != 4) recovery.endSignupVerification()
                    change { it.copy(submitting = false, serverError = message, step = toStep, code = "") }
                }
            )
        }
    }

    /** "Continue" on the success screen. [onFailure] is called when the sign-in did not work. */
    fun continueToApp(onFailure: () -> Unit) {
        val s = _state.value
        if (s.signingIn) return
        if (signInEmail.isEmpty() || signInPassword.isEmpty()) {
            // The app was restarted after the business was created, so the password is gone.
            toast.show("Business created. Please sign in.", ToastType.Info)
            onFailure()
            return
        }
        change { it.copy(signingIn = true) }
        viewModelScope.launch {
            val result = sessionManager.signInWithPassword(signInEmail, signInPassword)
            change { it.copy(signingIn = false) }
            if (result.isFailure) {
                toast.show("Business created. Please sign in.", ToastType.Info)
                onFailure()
            }
        }
    }

    fun copied() {
        toast.show("Business code copied.", ToastType.Success)
    }

    private companion object {
        const val K_STEP = "reg_step"
        const val K_NAME = "reg_name"
        const val K_EMAIL = "reg_email"
        const val K_PHONE = "reg_phone"
        const val K_BUSINESS = "reg_business"
        const val K_ROLES = "reg_roles"
        const val K_CODE = "reg_code"
    }
}
