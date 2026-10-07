package com.westly.nbms.core.session

import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role

data class SessionUser(
    val uid: String,
    val businessId: String,
    val role: Role,
    val name: String,
    val email: String?,
    val phone: String?,
    val status: String,
    val usesPin: Boolean
)

data class Business(
    val id: String,
    val name: String,
    val code: String,
    val enabledRoles: Set<Role>,
    val currency: String = "NGN",
    val currencySymbol: String = "₦",
    val timezone: String = "Africa/Lagos",
    val businessType: String = "hotel",
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String? = null
)

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(
        val user: SessionUser,
        val business: Business,
        val modules: Set<ModuleKey>
    ) : SessionState
    data class NoAccess(val reason: String) : SessionState
}
