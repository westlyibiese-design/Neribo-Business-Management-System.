package com.westly.nbms.features.messages

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRulesTest {

    private fun msg(
        id: String,
        name: String = "Sender $id",
        email: String = "$id@example.com",
        subject: String? = null,
        body: String = "Hello",
        status: String = "read",
        reply: ReplyStatus = ReplyStatus.NONE
    ) = InboxMessage(id = id, name = name, email = email, subject = subject, message = body, status = status, replyStatus = reply)

    private val sample = listOf(
        msg("a", name = "Ada Obi", subject = "Room for two", status = "new"),
        msg("b", name = "Bola Ade", email = "bola@hotelfan.org", body = "Is breakfast included?", status = "read", reply = ReplyStatus.REPLIED),
        msg("c", name = "Chidi", subject = "Wedding hall", body = "We need the GARDEN for 200 guests", status = "new", reply = ReplyStatus.PENDING),
        msg("d", name = "Dayo", status = "read", reply = ReplyStatus.NONE)
    )

    // ---- filters

    @Test fun allKeepsEverything() {
        assertEquals(listOf("a", "b", "c", "d"), filterMessages(sample, "", MessageFilter.ALL).map { it.id })
    }

    @Test fun unreadKeepsOnlyNew() {
        assertEquals(listOf("a", "c"), filterMessages(sample, "", MessageFilter.UNREAD).map { it.id })
    }

    @Test fun unrepliedKeepsEverythingNotReplied() {
        assertEquals(listOf("a", "c", "d"), filterMessages(sample, "", MessageFilter.UNREPLIED).map { it.id })
    }

    // ---- search

    @Test fun searchMatchesNameEmailSubjectAndBodyIgnoringCase() {
        assertEquals(listOf("a"), filterMessages(sample, "ada obi", MessageFilter.ALL).map { it.id })
        assertEquals(listOf("b"), filterMessages(sample, "HOTELFAN", MessageFilter.ALL).map { it.id })
        assertEquals(listOf("c"), filterMessages(sample, "wedding", MessageFilter.ALL).map { it.id })
        assertEquals(listOf("c"), filterMessages(sample, "garden", MessageFilter.ALL).map { it.id })
        assertEquals(listOf("b"), filterMessages(sample, "BREAKFAST", MessageFilter.ALL).map { it.id })
    }

    @Test fun blankSearchMatchesAllAndNoMatchGivesEmpty() {
        assertEquals(4, filterMessages(sample, "   ", MessageFilter.ALL).size)
        assertTrue(filterMessages(sample, "zzz-nothing", MessageFilter.ALL).isEmpty())
    }

    @Test fun searchAndFilterCombine() {
        assertEquals(listOf("a"), filterMessages(sample, "room", MessageFilter.UNREAD).map { it.id })
        assertEquals(listOf("d"), filterMessages(sample, "dayo", MessageFilter.UNREPLIED).map { it.id })
        assertTrue(filterMessages(sample, "ada", MessageFilter.UNREAD).isNotEmpty())
        assertTrue(filterMessages(sample, "bola", MessageFilter.UNREPLIED).isEmpty()) // b is replied
    }

    // ---- header subtitle (exact strings, no singular form)

    @Test fun subtitleShowsTotalAndUnread() {
        assertEquals("3 messages · 1 unread", headerSubtitle(3, 1, loaded = true))
    }

    @Test fun subtitleLeavesOutUnreadWhenZero() {
        assertEquals("3 messages", headerSubtitle(3, 0, loaded = true))
        assertEquals("0 messages", headerSubtitle(0, 0, loaded = true))
    }

    @Test fun subtitleHasNoSingularForm() {
        assertEquals("1 messages · 1 unread", headerSubtitle(1, 1, loaded = true))
        assertEquals("1 messages", headerSubtitle(1, 0, loaded = true))
    }

    @Test fun subtitleBeforeFirstLoadIsTheDescription() {
        assertEquals("Enquiries submitted through the public website contact form", headerSubtitle(0, 0, loaded = false))
        assertEquals("Enquiries submitted through the public website contact form", headerSubtitle(5, 2, loaded = false))
    }

    // ---- unread maths

    @Test fun unreadCountsNewMessages() {
        assertEquals(2, unreadOf(sample))
        assertEquals(0, unreadOf(emptyList()))
    }

    @Test fun markReadLowersTheCountOnce() {
        val after = markReadLocal(sample, "a", Instant.parse("2026-10-09T12:00:00Z"))
        assertEquals(1, unreadOf(after))
        assertFalse(after.first { it.id == "a" }.isNew)
        assertEquals(Instant.parse("2026-10-09T12:00:00Z"), after.first { it.id == "a" }.readAt)
        // Doing it again, or on a read or unknown message, changes nothing.
        assertEquals(after, markReadLocal(after, "a"))
        assertEquals(sample, markReadLocal(sample, "b"))
        assertEquals(sample, markReadLocal(sample, "nope"))
    }

    @Test fun removingAnUnreadMessageLowersTheCount() {
        val after = removeLocal(sample, "c")
        assertEquals(listOf("a", "b", "d"), after.map { it.id })
        assertEquals(1, unreadOf(after))
        assertEquals(2, unreadOf(removeLocal(sample, "b")))
        assertEquals(2, unreadOf(removeLocal(sample, "nope")))
    }

    @Test fun replyStatusChangeKeepsTheUnreadCount() {
        val now = Instant.parse("2026-10-09T12:00:00Z")
        val after = setReplyLocal(sample, "a", ReplyStatus.REPLIED, now)
        assertEquals(ReplyStatus.REPLIED, after.first { it.id == "a" }.replyStatus)
        assertEquals(now, after.first { it.id == "a" }.repliedAt)
        assertEquals(2, unreadOf(after))
        val back = setReplyLocal(after, "a", ReplyStatus.NONE, now)
        assertEquals(null, back.first { it.id == "a" }.repliedAt)
    }

    // ---- reply status mapping: wire <-> enum <-> label

    @Test fun replyStatusWireValuesAndLabels() {
        assertEquals("none", ReplyStatus.NONE.wire)
        assertEquals("pending", ReplyStatus.PENDING.wire)
        assertEquals("replied", ReplyStatus.REPLIED.wire)
        assertEquals("Not replied", ReplyStatus.NONE.label)
        assertEquals("Reply pending", ReplyStatus.PENDING.label)
        assertEquals("Replied", ReplyStatus.REPLIED.label)
    }

    @Test fun replyStatusFromWireRoundTripsAndDefaultsToNone() {
        ReplyStatus.entries.forEach { assertEquals(it, ReplyStatus.fromWire(it.wire)) }
        assertEquals(ReplyStatus.NONE, ReplyStatus.fromWire(null))
        assertEquals(ReplyStatus.NONE, ReplyStatus.fromWire(""))
        assertEquals(ReplyStatus.NONE, ReplyStatus.fromWire("mystery"))
        assertEquals(ReplyStatus.REPLIED, ReplyStatus.fromWire(" Replied "))
    }

    @Test fun filterLabelsAreCapitalised() {
        assertEquals(listOf("All", "Unread", "Unreplied"), MessageFilter.entries.map { it.label })
    }

    // ---- reply by email

    @Test fun replySubjectUsesTheSubject() {
        assertEquals("Re: Room for two", replySubject("Room for two", "Sunrise Hotel"))
        assertEquals("Re: Room for two", replySubject("  Room for two ", "Sunrise Hotel"))
    }

    @Test fun replySubjectFallsBackToTheBusinessName() {
        assertEquals("Re: Your enquiry to Sunrise Hotel", replySubject(null, "Sunrise Hotel"))
        assertEquals("Re: Your enquiry to Sunrise Hotel", replySubject("   ", "Sunrise Hotel"))
    }

    @Test fun mailtoLinkEncodesTheSubject() {
        assertEquals("mailto:ada@example.com", mailtoLink("ada@example.com"))
        assertEquals(
            "mailto:ada@example.com?subject=Re%3A%20Room%20for%20two",
            mailtoLink(" ada@example.com ", "Re: Room for two")
        )
        assertEquals("mailto:a@b.com?subject=Caf%C3%A9%20%26%20bar", mailtoLink("a@b.com", "Café & bar"))
    }

    @Test fun telLinkRemovesSpaces() {
        assertEquals("tel:+2348031234567", telLink("+234 803 123 4567"))
    }

    @Test fun firstLineSkipsBlankLines() {
        assertEquals("Hello", firstLine("\n  \nHello\nSecond line"))
        assertEquals("", firstLine("   "))
        assertEquals("One", firstLine("One"))
    }
}
