package com.westly.nbms.features.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class MediaApiRequestTest {

    private fun image(size: Int = 200): ProcessedImage =
        ProcessedImage(ByteArray(size) { (it % 251).toByte() }, "image/jpeg", "photo.jpg")

    private fun failureMessage(block: () -> Unit): String {
        try {
            block()
        } catch (e: MediaException) {
            return e.message.orEmpty()
        }
        fail("Expected a MediaException")
        return ""
    }

    // ---- request body ----

    @Test fun bodyHasExactlyTheFourFieldNames() {
        val body = buildMediaUploadBody("gallery", image())
        assertEquals(setOf("folder", "fileName", "contentType", "base64"), body.keys)
        assertEquals("gallery", body["folder"]!!.jsonPrimitive.content)
        assertEquals("photo.jpg", body["fileName"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", body["contentType"]!!.jsonPrimitive.content)
    }

    @Test fun base64HasNoLineBreaksNoPrefixAndRoundTrips() {
        val img = image(size = 5000)   // large enough that a MIME encoder would have inserted line breaks
        val text = buildMediaUploadBody("rooms", img)["base64"]!!.jsonPrimitive.content
        assertFalse(text.contains('\n'))
        assertFalse(text.contains('\r'))
        assertFalse(text.startsWith("data:"))
        assertTrue(text.isNotEmpty())
        assertEquals(img.bytes.toList(), Base64.getDecoder().decode(text).toList())
    }

    @Test fun bodyIsPlainJson() {
        val body = buildMediaUploadBody("venues", image())
        val parsed = Json.parseToJsonElement(body.toString()).jsonObject
        assertEquals("venues", parsed["folder"]!!.jsonPrimitive.content)
    }

    // ---- reply mapping ----

    @Test fun okReplyReturnsTheUrl() {
        val url = "https://abc.supabase.co/storage/v1/object/public/nbms-media/biz/gallery/x.jpg"
        assertEquals(url, parseMediaUploadReply("""{"ok":true,"url":"$url"}"""))
    }

    @Test fun okFalseBecomesAnExceptionWithTheServersMessage() {
        val msg = failureMessage { parseMediaUploadReply("""{"ok":false,"error":"Invalid upload folder."}""") }
        assertEquals("Invalid upload folder.", msg)
    }

    @Test fun okFalseWithoutMessageUsesTheGenericOne() {
        val msg = failureMessage { parseMediaUploadReply("""{"ok":false}""") }
        assertEquals("Upload failed. Please try again.", msg)
    }

    @Test fun nonHttpsUrlIsRejected() {
        val msg = failureMessage { parseMediaUploadReply("""{"ok":true,"url":"http://example.com/a.jpg"}""") }
        assertEquals("Upload failed. Please try again.", msg)
    }

    @Test fun missingOrBlankUrlIsRejected() {
        assertEquals("Upload failed. Please try again.", failureMessage { parseMediaUploadReply("""{"ok":true}""") })
        assertEquals("Upload failed. Please try again.", failureMessage { parseMediaUploadReply("""{"ok":true,"url":""}""") })
    }

    @Test fun garbageReplyIsAGenericFailure() {
        assertEquals("Upload failed. Please try again.", failureMessage { parseMediaUploadReply("<html>Bad gateway</html>") })
        assertEquals("Upload failed. Please try again.", failureMessage { parseMediaUploadReply("") })
    }

    @Test fun messagesMatchTheSpec() {
        assertEquals("Can't reach the server. Check your connection and try again.", MSG_MEDIA_NETWORK)
        assertEquals("Upload failed. Please try again.", MSG_MEDIA_UPLOAD_FAILED)
        assertEquals(60_000L, MEDIA_UPLOAD_TIMEOUT_MS)
    }
}
