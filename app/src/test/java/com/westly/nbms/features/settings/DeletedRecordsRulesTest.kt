package com.westly.nbms.features.settings

import com.westly.nbms.features.settings.models.DeletedRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class DeletedRecordsRulesTest {

    private fun rec(id: String, coll: String, label: String = "") = DeletedRecord(
        id = id, originalCollection = coll, originalDocumentId = "abcdefghijkl", label = label
    )

    @Test
    fun knownCollectionsHaveFriendlyLabels() {
        assertEquals("Room", collectionLabel("rooms"))
        assertEquals("Staff Account", collectionLabel("users"))
        assertEquals("Inventory Item", collectionLabel("inventory"))
        assertEquals("Housekeeping Task", collectionLabel("housekeeping_tasks"))
        assertEquals("Maintenance Request", collectionLabel("maintenance"))
    }

    @Test
    fun unknownCollectionsReplaceUnderscoresWithSpaces() {
        assertEquals("lost found", collectionLabel("lost_found"))
    }

    @Test
    fun pillColoursFollowTheSpec() {
        assertEquals(CollectionTone.BLUE, collectionTone("rooms"))
        assertEquals(CollectionTone.PURPLE, collectionTone("bookings"))
        assertEquals(CollectionTone.GREEN, collectionTone("guests"))
        assertEquals(CollectionTone.RED, collectionTone("users"))
        assertEquals(CollectionTone.YELLOW, collectionTone("sales"))
        assertEquals(CollectionTone.ORANGE, collectionTone("expenses"))
        assertEquals(CollectionTone.TEAL, collectionTone("inventory"))
        assertEquals(CollectionTone.GRAY, collectionTone("venues"))
    }

    @Test
    fun recordWithoutALabelShowsTheCollectionAndFirstEightIdCharacters() {
        assertEquals("Room · abcdefgh", displayLabel(rec("1", "rooms")))
        assertEquals("Room 101", displayLabel(rec("1", "rooms", label = "Room 101")))
    }

    @Test
    fun newestDeletionComesFirstAndUnstampedLast() {
        val times = mapOf("old" to 100L, "new" to 200L)
        val sorted = sortDeleted(listOf(rec("old", "rooms"), rec("none", "rooms"), rec("new", "rooms"))) { times[it.id] }
        assertEquals(listOf("new", "old", "none"), sorted.map { it.id })
    }

    @Test
    fun filterAndChipsWork() {
        val all = listOf(rec("1", "rooms"), rec("2", "guests"), rec("3", "rooms"))
        assertEquals(listOf("guests", "rooms"), distinctDeletedCollections(all).sortedBy { collectionLabel(it) })
        assertEquals(listOf("1", "3"), filterDeleted(all, "rooms").map { it.id })
        assertEquals(3, filterDeleted(all, null).size)
        // A chip whose records are all gone shows everything again.
        assertEquals(3, filterDeleted(all, "bookings").size)
    }

    @Test
    fun deletedByLineIncludesTheRoleLabel() {
        val r = DeletedRecord(deletedByName = "Ada", deletedByRole = "receptionist")
        assertEquals("Deleted by Ada (Receptionist)", deletedByText(r))
    }
}
