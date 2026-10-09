package com.westly.nbms.features.messages

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParsingTest {

    private val fullRow = """
        {"id":"m-1","business_id":"b-1","name":"Ada Obi","email":"ada@example.com","phone":"08031234567",
         "subject":"Room for two","message":"Hello,\nDo you have a room?","status":"new","reply_status":"pending",
         "created_at":"2026-10-09T12:30:00.123456+00:00","read_at":"2026-10-09T13:00:00Z","replied_at":null,"is_deleted":false}
    """.trimIndent()

    @Test fun fullRowParsesEveryField() {
        val m = parseMessageRow(fullRow).getOrThrow()
        assertEquals("m-1", m.id)
        assertEquals("b-1", m.businessId)
        assertEquals("Ada Obi", m.name)
        assertEquals("ada@example.com", m.email)
        assertEquals("08031234567", m.phone)
        assertEquals("Room for two", m.subject)
        assertEquals("Hello,\nDo you have a room?", m.message)
        assertTrue(m.isNew)
        assertEquals(ReplyStatus.PENDING, m.replyStatus)
        assertEquals(Instant.parse("2026-10-09T12:30:00.123456Z"), m.createdAt)
        assertEquals(Instant.parse("2026-10-09T13:00:00Z"), m.readAt)
        assertNull(m.repliedAt)
    }

    @Test fun rowWithNullsAndMissingKeysUsesSafeDefaults() {
        val m = parseMessageRow("""{"id":"m-2","name":null,"phone":null,"subject":null,"reply_status":null,"created_at":null}""").getOrThrow()
        assertEquals("m-2", m.id)
        assertEquals("", m.name)
        assertEquals("", m.email)
        assertEquals("", m.message)
        assertNull(m.phone)
        assertNull(m.subject)
        assertEquals(ReplyStatus.NONE, m.replyStatus)
        assertNull(m.createdAt)
        assertFalse(m.isNew)
    }

    @Test fun rowWithoutIdFails() {
        assertTrue(parseMessageRow("""{"name":"No id"}""").isFailure)
    }

    @Test fun unknownReplyStatusBecomesNone() {
        val m = parseMessageRow("""{"id":"m-3","reply_status":"escalated"}""").getOrThrow()
        assertEquals(ReplyStatus.NONE, m.replyStatus)
    }

    @Test fun unknownExtraKeysAreIgnored() {
        val m = parseMessageRow("""{"id":"m-4","name":"Bola","brand_new_column":{"a":1},"another":[1,2,3]}""").getOrThrow()
        assertEquals("Bola", m.name)
    }

    @Test fun blankPhoneAndSubjectBecomeNull() {
        val m = parseMessageRow("""{"id":"m-5","phone":"  ","subject":""}""").getOrThrow()
        assertNull(m.phone)
        assertNull(m.subject)
    }

    // ---- timestamps

    @Test fun timestampsTolerateOffsetFractionAndSpace() {
        val expected = Instant.parse("2026-10-09T12:00:00Z")
        assertEquals(expected, parseMessageInstant("2026-10-09T12:00:00Z"))
        assertEquals(expected, parseMessageInstant("2026-10-09T12:00:00+00:00"))
        assertEquals(expected, parseMessageInstant("2026-10-09 12:00:00+00"))
        assertEquals(expected, parseMessageInstant("2026-10-09T13:00:00+01:00"))
        assertEquals(Instant.parse("2026-10-09T12:00:00.5Z"), parseMessageInstant("2026-10-09T12:00:00.500+00:00"))
    }

    @Test fun unparseableTimestampIsNull() {
        assertNull(parseMessageInstant(null))
        assertNull(parseMessageInstant(""))
        assertNull(parseMessageInstant("yesterday"))
        assertNull(parseMessageInstant("2026-10-09"))
    }

    // ---- messages-list reply

    @Test fun listReplyParsesMessagesInOrder() {
        val reply = """{"ok":true,"messages":[{"id":"a","status":"new"},{"id":"b","status":"read"}]}"""
        val list = parseMessagesReply(reply).getOrThrow()
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals(1, unreadOf(list))
    }

    @Test fun listReplyWithNoMessagesKeyIsEmpty() {
        assertTrue(parseMessagesReply("""{"ok":true}""").getOrThrow().isEmpty())
        assertTrue(parseMessagesReply("""{"ok":true,"messages":[]}""").getOrThrow().isEmpty())
    }

    @Test fun listReplySkipsBrokenAndDeletedRows() {
        val reply = """{"ok":true,"messages":[{"name":"no id"},{"id":"x","is_deleted":true},{"id":"y"}]}"""
        assertEquals(listOf("y"), parseMessagesReply(reply).getOrThrow().map { it.id })
    }

    @Test fun failureReplyCarriesServerText() {
        val r = parseMessagesReply("""{"ok":false,"error":"Not allowed for your role."}""")
        assertTrue(r.isFailure)
        assertEquals("Not allowed for your role.", r.exceptionOrNull()?.message)
    }

    @Test fun failureReplyWithoutTextUsesGenericMessage() {
        assertEquals(MSG_LOAD_GENERIC, parseMessagesReply("""{"ok":false}""").exceptionOrNull()?.message)
        assertEquals(MSG_LOAD_GENERIC, parseMessagesReply("not json at all").exceptionOrNull()?.message)
    }

    // ---- messages-update reply

    @Test fun updateReplyOk() {
        assertTrue(parseUpdateReply("""{"ok":true}""").isSuccess)
    }

    @Test fun updateReplyFailureCarriesServerText() {
        val r = parseUpdateReply("""{"ok":false,"error":"Message not found."}""")
        assertEquals("Message not found.", r.exceptionOrNull()?.message)
        assertEquals(MSG_UPDATE_GENERIC, parseUpdateReply("garbage").exceptionOrNull()?.message)
    }

    @Test fun serverErrorIsPulledFromRawBodies() {
        assertEquals("Boom", messagesServerError(null, "", """{"ok":false,"error":"Boom"}"""))
        assertNull(messagesServerError("no error here"))
    }
}
