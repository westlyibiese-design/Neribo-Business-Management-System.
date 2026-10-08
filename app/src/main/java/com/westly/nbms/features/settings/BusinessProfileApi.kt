package com.westly.nbms.features.settings

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Updates the business record (name, currency, timezone). Calls the `update-business-profile` Edge Function. */
interface BusinessProfileApi {
    suspend fun update(name: String, currency: String, timezone: String): Result<Unit>
}

private const val PROFILE_MSG_NETWORK = "Can't reach the server. Check your connection and try again."
private const val PROFILE_MSG_GENERIC = "Something went wrong. Please try again."

private val profileJson = Json { ignoreUnknownKeys = true }
private val profileErrorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

internal fun profileRequestBody(name: String, currency: String, timezone: String): JsonObject = JsonObject(
    mapOf(
        "name" to JsonPrimitive(name),
        "currency" to JsonPrimitive(currency),
        "timezone" to JsonPrimitive(timezone)
    )
)

internal fun profileServerError(vararg candidates: String?): String? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        profileErrorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
    }
    return null
}

internal fun parseProfileReply(text: String): Result<Unit> = try {
    val obj = profileJson.parseToJsonElement(text).jsonObject
    if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) {
        Result.success(Unit)
    } else {
        Result.failure(Exception(obj["error"]?.jsonPrimitive?.contentOrNull ?: PROFILE_MSG_GENERIC))
    }
} catch (e: Exception) {
    Result.failure(Exception(PROFILE_MSG_GENERIC))
}

@Singleton
class BusinessProfileApiImpl @Inject constructor(
    private val supabase: SupabaseClient
) : BusinessProfileApi {

    override suspend fun update(name: String, currency: String, timezone: String): Result<Unit> {
        return try {
            val response = supabase.functions.invoke(
                function = "update-business-profile",
                body = profileRequestBody(name, currency, timezone)
            )
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                parseProfileReply(text)
            } else {
                Result.failure(Exception(profileServerError(text) ?: PROFILE_MSG_GENERIC))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            Result.failure(Exception(profileServerError(e.description, e.message, e.error) ?: PROFILE_MSG_GENERIC))
        } catch (e: Exception) {
            Result.failure(Exception(PROFILE_MSG_NETWORK))
        }
    }
}
