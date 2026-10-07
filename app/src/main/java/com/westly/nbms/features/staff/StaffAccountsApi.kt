package com.westly.nbms.features.staff

import com.westly.nbms.core.rbac.Role

/**
 * Staff account actions for the Super Admin (used by the Users page in Phase 8).
 * Each call invokes the Edge Function of the same name. A failure carries the server's plain message
 * in `Throwable.message`.
 */
interface StaffAccountsApi {
    /** Returns the new user's id. [pin] is for the 12 PIN roles only (4 to 6 digits). */
    suspend fun createUser(
        name: String,
        email: String,
        password: String,
        phone: String?,
        role: Role,
        pin: String?
    ): Result<String>

    suspend fun resetPassword(userId: String, newPassword: String): Result<Unit>

    suspend fun resetPin(userId: String, newPin: String): Result<Unit>

    /** [status] is "active" or "suspended". */
    suspend fun setStatus(userId: String, status: String): Result<Unit>
}
