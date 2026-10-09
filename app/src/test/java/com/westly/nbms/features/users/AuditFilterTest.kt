package com.westly.nbms.features.users

import com.westly.nbms.features.users.models.AuditLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditFilterTest {

    private val a = AuditLogEntry(id = "1", userName = "Ada Obi", action = "check_in:101", collection = "checkins", documentId = "DOC-ABC-111")
    private val b = AuditLogEntry(id = "2", userName = "Tunde", action = "user_created", collection = "users", documentId = "xyz999")
    private val c = AuditLogEntry(id = "3", userName = "Ngozi", action = "new_sale", collection = "sales", documentId = "sale-0001")
    private val all = listOf(a, b, c)

    @Test
    fun emptySearchAndAllCollectionsKeepsEverything() {
        assertEquals(all, filterAuditEntries(all, "", null))
    }

    @Test
    fun searchMatchesActionIgnoringCase() {
        assertEquals(listOf(a), filterAuditEntries(all, "CHECK_IN", null))
    }

    @Test
    fun searchMatchesUserNameIgnoringCase() {
        assertEquals(listOf(b), filterAuditEntries(all, "tUnDe", null))
    }

    @Test
    fun searchMatchesPartOfTheDocumentId() {
        assertEquals(listOf(b), filterAuditEntries(all, "z99", null))
    }

    @Test
    fun documentIdMatchIsExactCase() {
        assertTrue(filterAuditEntries(all, "doc-abc", null).isEmpty())
        assertEquals(listOf(a), filterAuditEntries(all, "DOC-ABC", null))
    }

    @Test
    fun collectionFilterKeepsOnlyThatCollection() {
        assertEquals(listOf(c), filterAuditEntries(all, "", "sales"))
    }

    @Test
    fun searchAndCollectionMustBothMatch() {
        assertTrue(filterAuditEntries(all, "tunde", "sales").isEmpty())
        assertEquals(listOf(b), filterAuditEntries(all, "tunde", "users"))
    }

    @Test
    fun surroundingSpacesInSearchAreIgnored() {
        assertEquals(listOf(b), filterAuditEntries(all, "  tunde ", null))
    }

    @Test
    fun newestFirstPutsUnstampedEntriesLast() {
        val times = mapOf("1" to 100L, "2" to null, "3" to 300L)
        val sorted = sortNewestFirst(all) { times[it.id] }
        assertEquals(listOf("3", "1", "2"), sorted.map { it.id })
    }

    @Test
    fun collectionsAreDistinctAndSorted() {
        val more = all + AuditLogEntry(id = "4", collection = "users") + AuditLogEntry(id = "5", collection = "")
        assertEquals(listOf("checkins", "sales", "users"), distinctCollections(more))
    }

    @Test
    fun longDocumentIdIsCutAtTwelveWithEllipsis() {
        assertEquals("0123456789ab…", shortDocId("0123456789abcdef"))
    }

    @Test
    fun shortDocumentIdIsKeptWhole() {
        assertEquals("abc", shortDocId("abc"))
        assertEquals("0123456789ab", shortDocId("0123456789ab"))
    }

    @Test
    fun actionPrefixIsTheTextBeforeTheFirstColon() {
        assertEquals("check_in", actionPrefix("check_in:room 101:x"))
        assertEquals("new_sale", actionPrefix("new_sale"))
    }

    @Test
    fun actionColoursFollowTheWestlyMap() {
        assertEquals(AuditTone.GREEN, toneForAction("check_in"))
        assertEquals(AuditTone.BLUE, toneForAction("check_out:101"))
        assertEquals(AuditTone.TEAL, toneForAction("walk_in_checkin"))
        assertEquals(AuditTone.GRAY, toneForAction("admin_login"))
        assertEquals(AuditTone.GRAY, toneForAction("pin_login"))
        assertEquals(AuditTone.PURPLE, toneForAction("user_created"))
        assertEquals(AuditTone.RED, toneForAction("user_suspended"))
        assertEquals(AuditTone.GREEN, toneForAction("user_active"))
        assertEquals(AuditTone.GREEN, toneForAction("user_reactivated"))
        assertEquals(AuditTone.RED, toneForAction("soft_delete"))
        assertEquals(AuditTone.GREEN, toneForAction("restore"))
        assertEquals(AuditTone.BLUE, toneForAction("room_created"))
        assertEquals(AuditTone.YELLOW, toneForAction("room_updated"))
        assertEquals(AuditTone.ORANGE, toneForAction("new_sale"))
    }

    @Test
    fun unknownActionsAreMuted() {
        assertEquals(AuditTone.MUTED, toneForAction("roles_updated"))
        assertEquals(AuditTone.MUTED, toneForAction(""))
    }

    @Test
    fun entryCountTextUsesSingularForOne() {
        assertEquals("0 entries", entryCountText(0))
        assertEquals("1 entry", entryCountText(1))
        assertEquals("2 entries", entryCountText(2))
    }
}
