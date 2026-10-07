package com.westly.nbms.features.device

import android.os.Build
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceInfo(
    val registrationId: String,
    val deviceId: String,
    val deviceLabel: String,
    val createdAt: Instant?,
    val lastUsedAt: Instant?,
    val hasPin: Boolean
)

/** The server said no. [message] is the server's own text; [status] is the HTTP status (0 = could not reach the server). */
open class DeviceApiException(message: String, val status: Int = 0) : Exception(message)

/** HTTP 423: too many wrong PINs, unlocking is paused. */
class DeviceLockedException(message: String) : DeviceApiException(message, 423)

interface DeviceApi {
    suspend fun list(): Result<List<DeviceInfo>>
    suspend fun setPin(deviceId: String, deviceLabel: String, pin: String): Result<Unit>
    /** Failure message = server text; 423 becomes [DeviceLockedException]. */
    suspend fun verifyPin(deviceId: String, pin: String): Result<Unit>
    suspend fun revoke(registrationId: String): Result<Unit>
}

/** "Samsung SM-A135F" */
fun deviceLabel(): String {
    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    return "$maker ${Build.MODEL.orEmpty()}".trim().ifEmpty { "This device" }
}

internal const val MSG_DEVICE_NETWORK = "Can't reach the server. Check your connection and try again."
internal const val MSG_DEVICE_GENERIC = "Something went wrong. Please try again."

private val deviceJson = Json { ignoreUnknownKeys = true }
private val deviceErrorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

private fun serverError(vararg candidates: String?): String? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        deviceErrorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
    }
    return null
}

private fun failureFor(status: Int, message: String?): DeviceApiException {
    val text = message ?: MSG_DEVICE_GENERIC
    return if (status == 423) DeviceLockedException(text) else DeviceApiException(text, status)
}

internal fun parseDeviceList(obj: JsonObject): List<DeviceInfo> {
    val arr: JsonArray = obj["devices"]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el.jsonObject
        val reg = o["registrationId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val dev = o["deviceId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        DeviceInfo(
            registrationId = reg,
            deviceId = dev,
            deviceLabel = o["deviceLabel"]?.jsonPrimitive?.contentOrNull ?: "Device",
            createdAt = parseInstant(o["createdAt"]?.jsonPrimitive?.contentOrNull),
            lastUsedAt = parseInstant(o["lastUsedAt"]?.jsonPrimitive?.contentOrNull),
            hasPin = o["hasPin"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }
}

private fun parseInstant(s: String?): Instant? =
    if (s.isNullOrBlank()) null else try { Instant.parse(s) } catch (_: Exception) { null }

/** Calls the four Device Lock Edge Functions through the signed-in Supabase client (it attaches the user's token). */
@Singleton
class DeviceApiImpl @Inject constructor(
    private val supabase: SupabaseClient
) : DeviceApi {

    override suspend fun list(): Result<List<DeviceInfo>> =
        call("device-list", buildJsonObject { }).mapCatching { parseDeviceList(it) }

    override suspend fun setPin(deviceId: String, deviceLabel: String, pin: String): Result<Unit> =
        call(
            "device-pin-set",
            buildJsonObject {
                put("deviceId", deviceId)
                put("deviceLabel", deviceLabel)
                put("pin", pin)
            }
        ).map { }

    override suspend fun verifyPin(deviceId: String, pin: String): Result<Unit> =
        call(
            "device-pin-verify",
            buildJsonObject {
                put("deviceId", deviceId)
                put("pin", pin)
            }
        ).map { }

    override suspend fun revoke(registrationId: String): Result<Unit> =
        call("device-revoke", buildJsonObject { put("registrationId", registrationId) }).map { }

    private suspend fun call(function: String, body: JsonObject): Result<JsonObject> {
        return try {
            val response = supabase.functions.invoke(function = function, body = body)
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                parseReply(text)
            } else {
                Result.failure(failureFor(response.status.value, serverError(text)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            Result.failure(failureFor(e.statusCode, serverError(e.description, e.message, e.error)))
        } catch (e: Exception) {
            Result.failure(DeviceApiException(MSG_DEVICE_NETWORK, 0))
        }
    }

    private fun parseReply(text: String): Result<JsonObject> = try {
        val obj = deviceJson.parseToJsonElement(text).jsonObject
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            Result.success(obj)
        } else {
            Result.failure(DeviceApiException(obj["error"]?.jsonPrimitive?.contentOrNull ?: MSG_DEVICE_GENERIC, 400))
        }
    } catch (e: Exception) {
        Result.failure(DeviceApiException(MSG_DEVICE_GENERIC, 500))
    }
}
