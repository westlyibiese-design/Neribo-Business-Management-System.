package com.westly.nbms.features.staff

import com.westly.nbms.core.rbac.Role
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

internal const val MSG_STAFF_NETWORK = "Can't reach the server. Check your connection and try again."
internal const val MSG_STAFF_GENERIC = "Something went wrong. Please try again."

private val staffJson = Json { ignoreUnknownKeys = true }
private val staffErrorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** Pulls the `error` text out of a server reply such as {"ok":false,"error":"..."}; null when there is none. */
internal fun staffServerError(vararg candidates: String?): String? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        staffErrorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
    }
    return null
}

/** Reads a reply body. Success returns the JSON object; `{ok:false,error}` becomes a failure with the server's message. */
internal fun parseStaffReply(text: String): Result<JsonObject> = try {
    val obj = staffJson.parseToJsonElement(text).jsonObject
    if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) {
        Result.success(obj)
    } else {
        Result.failure(Exception(obj["error"]?.jsonPrimitive?.contentOrNull ?: MSG_STAFF_GENERIC))
    }
} catch (e: Exception) {
    Result.failure(Exception(MSG_STAFF_GENERIC))
}

/** Calls the staff Edge Functions through the signed-in Supabase client (it attaches the user's token). */
@Singleton
class StaffAccountsApiImpl @Inject constructor(
    private val supabase: SupabaseClient
) : StaffAccountsApi {

    override suspend fun createUser(
        name: String,
        email: String,
        password: String,
        phone: String?,
        role: Role,
        pin: String?
    ): Result<String> {
        val body = buildJsonObject {
            put("name", name.trim())
            put("email", email.trim())
            put("password", password)
            val cleanPhone = phone?.trim().orEmpty()
            if (cleanPhone.isNotEmpty()) put("phone", cleanPhone)
            put("role", role.key)
            if (!pin.isNullOrEmpty()) put("pin", pin)
        }
        return call("create-user", body).mapCatching { obj ->
            obj["userId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw Exception(MSG_STAFF_GENERIC)
        }
    }

    override suspend fun resetPassword(userId: String, newPassword: String): Result<Unit> {
        val body = buildJsonObject {
            put("userId", userId)
            put("newPassword", newPassword)
        }
        return call("reset-password", body).map { }
    }

    override suspend fun resetPin(userId: String, newPin: String): Result<Unit> {
        val body = buildJsonObject {
            put("userId", userId)
            put("newPin", newPin)
        }
        return call("reset-pin", body).map { }
    }

    override suspend fun setStatus(userId: String, status: String): Result<Unit> {
        val body = buildJsonObject {
            put("userId", userId)
            put("status", status)
        }
        return call("set-user-status", body).map { }
    }

    private suspend fun call(function: String, body: JsonObject): Result<JsonObject> {
        return try {
            val response = supabase.functions.invoke(function = function, body = body)
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                parseStaffReply(text)
            } else {
                Result.failure(Exception(staffServerError(text) ?: MSG_STAFF_GENERIC))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            Result.failure(Exception(staffServerError(e.description, e.message, e.error) ?: MSG_STAFF_GENERIC))
        } catch (e: Exception) {
            Result.failure(Exception(MSG_STAFF_NETWORK))
        }
    }
}
