package com.westly.nbms.features.auth

import com.westly.nbms.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Keeps a session in memory only: nothing is read from or written to storage. */
private class MemorySessionManager : io.github.jan.supabase.auth.SessionManager {
    private var current: UserSession? = null
    override suspend fun saveSession(session: UserSession) { current = session }
    override suspend fun loadSession(): UserSession? = current
    override suspend fun deleteSession() { current = null }
}

/**
 * Builds the second, non-persistent Supabase client used only for password recovery.
 * It never loads, saves or refreshes a stored session, so it cannot touch the app's real sign-in.
 */
internal fun createRecoverySupabaseClient(): SupabaseClient =
    createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
        install(Auth) {
            autoLoadFromStorage = false
            autoSaveToStorage = false
            alwaysAutoRefresh = false
            sessionManager = MemorySessionManager()
        }
    }

/** The 6-digit code was wrong or has expired. */
class InvalidRecoveryCodeException : Exception("Invalid or expired code")

/** Password-recovery calls, made through the separate recovery client. */
@Singleton
class RecoveryClient @Inject constructor(
    @Named("recovery") private val client: SupabaseClient
) {
    /** Asks Supabase to email a 6-digit code. */
    suspend fun sendResetCode(email: String) {
        client.auth.resetPasswordForEmail(email.trim())
    }

    /** Registration: emails a 6-digit code to the address (a new account is prepared for it if none exists). */
    suspend fun sendSignupCode(email: String) {
        client.auth.signInWith(OTP) {
            this.email = email.trim()
            createUser = true
        }
    }

    /**
     * Registration: checks the code and returns the access token that proves this email belongs to the person.
     * The session stays in memory only. Throws [InvalidRecoveryCodeException] when the code is wrong or expired.
     */
    suspend fun verifySignupCode(email: String, code: String): String {
        try {
            client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = code.trim())
        } catch (e: RestException) {
            throw InvalidRecoveryCodeException()
        }
        return client.auth.currentAccessTokenOrNull() ?: throw InvalidRecoveryCodeException()
    }

    /** Registration: forgets the in-memory verification session. Never throws. */
    suspend fun endSignupVerification() {
        try {
            client.auth.signOut()
        } catch (_: Exception) {
            // In memory only, so a failed sign-out changes nothing.
        }
    }

    /**
     * Checks the code, sets the new password, then signs out of the recovery client only.
     * Throws when the code is wrong or expired.
     */
    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        try {
            client.auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email.trim(), token = code.trim())
        } catch (e: RestException) {
            throw InvalidRecoveryCodeException()
        }
        try {
            client.auth.updateUser { password = newPassword }
        } finally {
            try {
                client.auth.signOut()
            } catch (_: Exception) {
                // The recovery client is in memory only, so a failed sign-out changes nothing.
            }
        }
    }
}
