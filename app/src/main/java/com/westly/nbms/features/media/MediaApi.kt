package com.westly.nbms.features.media

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

internal const val MSG_MEDIA_NETWORK = "Can't reach the server. Check your connection and try again."
internal const val MSG_MEDIA_UPLOAD_FAILED = "Upload failed. Please try again."
internal const val MEDIA_UPLOAD_TIMEOUT_MS = 60_000L

/** An upload problem whose message is already written for the person to read. */
class MediaException(message: String) : Exception(message)

private val mediaJson = Json { ignoreUnknownKeys = true }
private val mediaErrorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** The exact request body of `media-upload`: { folder, fileName, contentType, base64 } (base64 without line breaks). */
internal fun buildMediaUploadBody(folder: String, image: ProcessedImage): JsonObject = buildJsonObject {
    put("folder", folder)
    put("fileName", image.fileName)
    put("contentType", image.contentType)
    put("base64", Base64.getEncoder().encodeToString(image.bytes))
}

/** Reads the reply of `media-upload`. Returns the https url, or throws [MediaException] with the server's message. */
internal fun parseMediaUploadReply(text: String): String {
    val obj = try {
        mediaJson.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw MediaException(MSG_MEDIA_UPLOAD_FAILED)
    }
    if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
        throw MediaException(obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: MSG_MEDIA_UPLOAD_FAILED)
    }
    val url = obj["url"]?.jsonPrimitive?.contentOrNull
    if (url.isNullOrBlank() || !url.startsWith("https://")) throw MediaException(MSG_MEDIA_UPLOAD_FAILED)
    return url
}

private fun serverError(vararg candidates: String?): String? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        mediaErrorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
    }
    return null
}

/** Talks to the `media-upload` and `media-delete` Edge Functions through the signed-in Supabase client. */
@Singleton
class MediaApi @Inject constructor(
    private val supabase: SupabaseClient
) {
    /** Uploads one processed picture and returns its public https url. Throws [MediaException] with a readable message. */
    suspend fun upload(folder: String, image: ProcessedImage): String {
        val body = buildMediaUploadBody(folder, image)
        val reply: String? = try {
            withTimeoutOrNull(MEDIA_UPLOAD_TIMEOUT_MS) {
                val response = supabase.functions.invoke(function = "media-upload", body = body)
                val replyText = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    throw MediaException(serverError(replyText) ?: MSG_MEDIA_UPLOAD_FAILED)
                }
                replyText
            }
        } catch (e: MediaException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            throw MediaException(serverError(e.description, e.message, e.error) ?: MSG_MEDIA_UPLOAD_FAILED)
        } catch (e: HttpRequestException) {
            throw MediaException(MSG_MEDIA_NETWORK)
        } catch (e: IOException) {
            throw MediaException(MSG_MEDIA_NETWORK)
        } catch (e: Exception) {
            throw MediaException(MSG_MEDIA_UPLOAD_FAILED)
        }
        // null = the 60 second timeout ran out
        val text = reply ?: throw MediaException(MSG_MEDIA_NETWORK)
        return parseMediaUploadReply(text)
    }

    /**
     * Best-effort removal of a file this business uploaded. Every error is swallowed.
     * Nothing calls this in Phase 32 (the photo fields never delete); it is here for later clean-up features.
     */
    suspend fun deleteByUrl(url: String) {
        if (url.isBlank()) return
        try {
            withTimeoutOrNull(MEDIA_UPLOAD_TIMEOUT_MS) {
                supabase.functions.invoke(function = "media-delete", body = buildJsonObject { put("url", url) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignored on purpose
        }
    }
}
