package com.westly.nbms.features.users

import com.westly.nbms.core.rbac.Rbac
import com.westly.nbms.core.rbac.Role
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Turns the business's staff roles on or off (Super Admin only). Calls the `set-business-roles` Edge Function. */
interface BusinessRolesApi {
    /** [enabledRoles] is the complete set of roles the business should use afterwards. */
    suspend fun setRoles(enabledRoles: Set<Role>): Result<Unit>
}

private const val ROLES_MSG_NETWORK = "Can't reach the server. Check your connection and try again."
private const val ROLES_MSG_GENERIC = "Something went wrong. Please try again."

private val rolesJson = Json { ignoreUnknownKeys = true }
private val errorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** Request body `{ "enabledRoles": ["manager", ...] }`: assignable roles only, in the order of the Role enum. */
internal fun rolesRequestBody(roles: Set<Role>): JsonObject {
    val keys = Rbac.assignableRoles.filter { it in roles }.map { JsonPrimitive(it.key) }
    return JsonObject(mapOf("enabledRoles" to JsonArray(keys)))
}

/** Pulls the `error` text out of a reply such as {"ok":false,"error":"..."}; null when there is none. */
internal fun rolesServerError(vararg candidates: String?): String? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        errorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
    }
    return null
}

/** `{ok:true,...}` is a success; anything else becomes a failure carrying the server's plain message. */
internal fun parseRolesReply(text: String): Result<Unit> = try {
    val obj = rolesJson.parseToJsonElement(text).jsonObject
    if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) {
        Result.success(Unit)
    } else {
        Result.failure(Exception(obj["error"]?.jsonPrimitive?.contentOrNull ?: ROLES_MSG_GENERIC))
    }
} catch (e: Exception) {
    Result.failure(Exception(ROLES_MSG_GENERIC))
}

@Singleton
class BusinessRolesApiImpl @Inject constructor(
    private val supabase: SupabaseClient
) : BusinessRolesApi {

    override suspend fun setRoles(enabledRoles: Set<Role>): Result<Unit> {
        return try {
            val response = supabase.functions.invoke(function = "set-business-roles", body = rolesRequestBody(enabledRoles))
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                parseRolesReply(text)
            } else {
                Result.failure(Exception(rolesServerError(text) ?: ROLES_MSG_GENERIC))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            Result.failure(Exception(rolesServerError(e.description, e.message, e.error) ?: ROLES_MSG_GENERIC))
        } catch (e: Exception) {
            Result.failure(Exception(ROLES_MSG_NETWORK))
        }
    }
}
