package com.westly.nbms.features.auth

import com.westly.nbms.core.rbac.Role
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

data class PinLoginUser(val name: String, val role: Role)

internal const val MSG_PIN_NETWORK = "Can't reach the server. Check your connection and try again."
internal const val MSG_PIN_GENERIC = "Invalid PIN. Please try again."
internal const val MSG_PIN_SESSION = "Could not start your session. Please try again."

/** What the `verify-pin` function sends back on success. */
internal data class PinReply(
    val accessToken: String,
    val refreshToken: String,
    val name: String,
    val role: Role
)

private val replyJson = Json { ignoreUnknownKeys = true }
private val errorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** Pulls the `error` text out of a server reply such as {"ok":false,"error":"..."}; null when there is none. */
internal fun pinServerError(vararg candidates: String?): String? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        errorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
    }
    return null
}

/** Reads a successful `verify-pin` reply. Failure carries a message that is safe to show. */
internal fun parsePinReply(text: String): Result<PinReply> = try {
    val obj = replyJson.parseToJsonElement(text).jsonObject
    val okFlag = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
    if (!okFlag) {
        Result.failure(Exception(pinServerError(text) ?: MSG_PIN_GENERIC))
    } else {
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
        val user = obj["user"]?.jsonObject
        val name = user?.get("name")?.jsonPrimitive?.contentOrNull
        val role = Role.fromKey(user?.get("role")?.jsonPrimitive?.contentOrNull)
        if (access.isNullOrBlank() || refresh.isNullOrBlank() || name == null || role == null) {
            Result.failure(Exception(MSG_PIN_SESSION))
        } else {
            Result.success(PinReply(access, refresh, name, role))
        }
    }
} catch (e: Exception) {
    Result.failure(Exception(MSG_PIN_SESSION))
}

/**
 * Shared-device sign-in. Calls the public `verify-pin` Edge Function, then hands the returned session to
 * Supabase. The app's SessionManager notices the new session and finishes the sign-in on its own.
 * The PIN is never logged or stored.
 */
class PinLoginService @Inject constructor(
    private val supabase: SupabaseClient,
    private val remembered: RememberedBusiness
) {
    suspend fun signIn(businessCode: String, pin: String): Result<PinLoginUser> {
        val code = businessCode.trim().uppercase()
        val body = buildJsonObject {
            put("businessCode", code)
            put("pin", pin)
        }

        val text: String = try {
            val response = supabase.functions.invoke(function = "verify-pin", body = body)
            val reply = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return Result.failure(Exception(pinServerError(reply) ?: MSG_PIN_GENERIC))
            }
            reply
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            return Result.failure(Exception(pinServerError(e.description, e.message, e.error) ?: MSG_PIN_GENERIC))
        } catch (e: Exception) {
            return Result.failure(Exception(MSG_PIN_NETWORK))
        }

        val reply = parsePinReply(text).getOrElse { return Result.failure(it) }

        try {
            // Finish importing even if the screen is closed meanwhile, so we never leave a half-made session.
            withContext(NonCancellable) {
                supabase.auth.importSession(
                    UserSession(
                        accessToken = reply.accessToken,
                        refreshToken = reply.refreshToken,
                        expiresIn = 3600,
                        tokenType = "bearer",
                        user = null
                    )
                )
                // The session manager identifies the person by the session's user id, so make sure it is filled in.
                if (supabase.auth.currentSessionOrNull()?.user == null) {
                    try {
                        supabase.auth.retrieveUserForCurrentSession(updateSession = true)
                    } catch (_: Exception) {
                        // The next token refresh fills it in; nothing else to do here.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(Exception(MSG_PIN_SESSION))
        }

        remembered.save(code)
        return Result.success(PinLoginUser(reply.name, reply.role))
    }
}
