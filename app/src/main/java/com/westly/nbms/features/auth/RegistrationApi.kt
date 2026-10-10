package com.westly.nbms.features.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CreateBusinessRequest(
    val ownerName: String,
    val email: String,
    val password: String,
    val phone: String? = null,
    val businessName: String,
    val businessType: String = "hotel",
    val enabledRoles: List<String>,
    /** Access token from the verified email code. The server takes the account from it. */
    val verificationToken: String
)

data class CreateBusinessResult(val businessId: String, val businessCode: String)

/** Thrown with a message that is safe to show to the person. */
class RegistrationException(message: String, val statusCode: Int? = null) : Exception(message)

internal const val MSG_REGISTER_GENERIC = "Could not create your business. Please try again."
internal const val MSG_REGISTER_NETWORK = "Can't reach the server. Check your connection and try again."
internal const val MSG_EMAIL_EXISTS = "An account with this email already exists."
internal const val MSG_VERIFY_EXPIRED = "Your email verification has expired. Please verify your email again."

/** Pulls the `error` text out of a server reply such as {"ok":false,"error":"..."}; null when there is none. */
internal fun extractServerError(vararg candidates: String?): String? {
    val pattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        pattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
        if (c.contains(MSG_EMAIL_EXISTS)) return MSG_EMAIL_EXISTS
    }
    return null
}

/** Calls the public `create-business` Edge Function. */
@Singleton
class RegistrationApi @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun createBusiness(request: CreateBusinessRequest): Result<CreateBusinessResult> {
        val body: JsonObject = json.encodeToJsonElement(request).jsonObject
        return try {
            val response = supabase.functions.invoke(function = "create-business", body = body)
            val text = response.bodyAsText()
            if (response.status.isSuccess()) parse(text) else Result.failure(failureFrom(text, response.status.value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            Result.failure(failureFrom(extractServerError(e.description, e.message, e.error), e.statusCode))
        } catch (e: Exception) {
            Result.failure(RegistrationException(MSG_REGISTER_NETWORK))
        }
    }

    private fun parse(text: String): Result<CreateBusinessResult> = try {
        val obj = json.parseToJsonElement(text).jsonObject
        val okFlag = obj["ok"]?.jsonPrimitive?.boolean ?: false
        val id = obj["businessId"]?.jsonPrimitive?.contentOrNull
        val code = obj["businessCode"]?.jsonPrimitive?.contentOrNull
        if (okFlag && !id.isNullOrBlank() && !code.isNullOrBlank()) {
            Result.success(CreateBusinessResult(id, code))
        } else {
            Result.failure(RegistrationException(obj["error"]?.jsonPrimitive?.contentOrNull ?: MSG_REGISTER_GENERIC))
        }
    } catch (e: Exception) {
        Result.failure(RegistrationException(MSG_REGISTER_GENERIC))
    }

    private fun failureFrom(text: String?, status: Int): RegistrationException {
        val message = extractServerError(text) ?: MSG_REGISTER_GENERIC
        return RegistrationException(message, status)
    }
}
