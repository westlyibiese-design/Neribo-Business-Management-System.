package com.westly.nbms.features.notifications

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.messaging.FirebaseMessaging
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.device.DeviceIdProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PushRegistrar"

private val Context.pushDataStore by preferencesDataStore(name = "nbms_push_prefs")
private val PUSH_ENABLED_KEY = booleanPreferencesKey("push_enabled")

/** The request sent to `register-push-token`. */
internal fun registerTokenBody(token: String, deviceId: String?, remove: Boolean): JsonObject = buildJsonObject {
    put("token", token)
    if (remove) {
        put("remove", true)
    } else {
        if (!deviceId.isNullOrBlank()) put("deviceId", deviceId)
        put("platform", "android")
    }
}

private val errorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/**
 * Keeps this phone's Firebase push token registered with the server while someone is signed in and
 * push is switched on for this device (DataStore key `push_enabled`, default true).
 *
 * It starts itself the first time it is injected (the bell does that as soon as the shell appears)
 * and then follows the session: signing in registers the token; signing out only forgets the
 * "already asked for permission" memory (the server moves a token to whoever registers it next).
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val supabase: SupabaseClient,
    private val deviceIds: DeviceIdProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var askedForUid: String? = null

    /** Whether push is on for this device. Default true. */
    val enabled: Flow<Boolean> = context.pushDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[PUSH_ENABLED_KEY] ?: true }
        .distinctUntilChanged()

    suspend fun isEnabled(): Boolean = enabled.first()

    init {
        NotificationChannels.ensure(context)
        scope.launch {
            var lastUid: String? = null
            session.state.collect { state ->
                when (state) {
                    is SessionState.SignedIn -> {
                        if (state.user.uid != lastUid) {
                            lastUid = state.user.uid
                            registerCurrentToken()
                        }
                    }
                    SessionState.SignedOut -> {
                        lastUid = null
                        askedForUid = null
                    }
                    else -> Unit
                }
            }
        }
    }

    /** True exactly once per sign-in: the caller should now show the Android permission prompt. */
    fun consumePermissionPrompt(uid: String): Boolean {
        if (askedForUid == uid) return false
        askedForUid = uid
        return true
    }

    /** Called by the messaging service when Firebase issues a new token. */
    fun onNewToken(token: String) {
        scope.launch {
            if (session.state.value is SessionState.SignedIn && isEnabled()) {
                try {
                    callRegister(token, remove = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not register the new token: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Registers this phone's current token when signed in and push is on. Failures are only logged. */
    suspend fun registerCurrentToken() {
        try {
            if (session.state.value !is SessionState.SignedIn || !isEnabled()) return
            callRegister(FirebaseMessaging.getInstance().token.await(), remove = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not register for push: ${e.javaClass.simpleName}")
        }
    }

    /** Switches push on for this phone and registers the token. */
    suspend fun enable(): Result<Unit> = try {
        context.pushDataStore.edit { it[PUSH_ENABLED_KEY] = true }
        NotificationChannels.ensure(context)
        callRegister(FirebaseMessaging.getInstance().token.await(), remove = false)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Switches push off for this phone: tells the server to forget the token, then deletes it on the phone.
     * If the server cannot be reached nothing changes, so the switch and the server stay in step.
     */
    suspend fun disable(): Result<Unit> = try {
        val messaging = FirebaseMessaging.getInstance()
        val token = messaging.token.await()
        callRegister(token, remove = true)
        messaging.deleteToken().await()
        context.pushDataStore.edit { it[PUSH_ENABLED_KEY] = false }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun callRegister(token: String, remove: Boolean) {
        val body = registerTokenBody(token, if (remove) null else deviceIds.get(), remove)
        val text: String = try {
            val response = supabase.functions.invoke(function = "register-push-token", body = body)
            val reply = response.bodyAsText()
            if (!response.status.isSuccess()) throw PushException(serverMessage(reply) ?: GENERIC)
            reply
        } catch (e: PushException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.github.jan.supabase.exceptions.RestException) {
            throw PushException(serverMessage(e.description, e.message, e.error) ?: GENERIC)
        } catch (e: Exception) {
            throw PushException("Can't reach the server. Check your connection and try again.")
        }
        if (!text.contains("\"ok\":true") && !text.contains("\"ok\": true")) {
            throw PushException(serverMessage(text) ?: GENERIC)
        }
    }

    private fun serverMessage(vararg candidates: String?): String? {
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            errorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
        }
        return null
    }

    private class PushException(message: String) : Exception(message)

    private companion object {
        const val GENERIC = "Something went wrong. Please try again."
    }
}
