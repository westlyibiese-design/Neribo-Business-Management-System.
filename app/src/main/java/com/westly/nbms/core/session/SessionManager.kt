package com.westly.nbms.core.session

import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val state: StateFlow<SessionState>
    suspend fun signInWithPassword(email: String, password: String): Result<Unit>
    /** Implemented in Phase 5. */
    suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit>
    suspend fun signOut()
    /** Re-reads membership + business. */
    suspend fun refresh()
}
