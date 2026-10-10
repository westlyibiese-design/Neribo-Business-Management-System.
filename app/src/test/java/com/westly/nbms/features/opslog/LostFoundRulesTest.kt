package com.westly.nbms.features.opslog

import com.westly.nbms.core.rbac.Role
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LostFoundRulesTest {

    // ── permissions ──

    @Test fun canCreateIsSuperAdminManagerAndHousekeepingOnly() {
        val allowed = Role.entries.filter { lostFoundCanCreate(it) }.toSet()
        assertEquals(setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.HOUSEKEEPING), allowed)
    }

    @Test fun canManageStatusIsSuperAdminAndManagerOnly() {
        val allowed = Role.entries.filter { lostFoundCanManageStatus(it) }.toSet()
        assertEquals(setOf(Role.SUPER_ADMIN, Role.MANAGER), allowed)
        assertFalse(lostFoundCanManageStatus(Role.HOUSEKEEPING))
    }

    // ── labels ──

    @Test fun statusKeysAndLabels() {
        assertEquals(listOf("stored", "returned_to_guest", "claimed", "disposed"), ItemStatus.entries.map { it.key })
        assertEquals(listOf("Stored", "Returned to Guest", "Claimed", "Disposed"), ItemStatus.entries.map { it.label })
        assertEquals(ItemStatus.CLAIMED, ItemStatus.fromKey("claimed"))
        assertNull(ItemStatus.fromKey("lost"))
    }

    @Test fun subtitleShowsTheCount() {
        assertEquals("0 item(s)", lostFoundSubtitle(0))
        assertEquals("3 item(s)", lostFoundSubtitle(3))
    }

    // ── sort and filters ──

    @Test fun sortIsNewestFoundFirstAndUnknownTimeLast() {
        val old = lfItem("old", foundAt = Instant.parse("2026-10-01T10:00:00Z"))
        val new = lfItem("new", foundAt = Instant.parse("2026-10-08T10:00:00Z"))
        val none = lfItem("none", foundAt = null)
        assertEquals(listOf("new", "old", "none"), sortLostFound(listOf(none, old, new)).map { it.id })
    }

    private val sample = listOf(
        lfItem("a", name = "Wristwatch", description = "Silver, leather strap", room = "201", foundBy = "Ada", status = ItemStatus.STORED),
        lfItem("b", name = "Phone charger", description = "White cable", room = "305", foundBy = "Chidi", status = ItemStatus.CLAIMED),
        lfItem("c", name = "Umbrella", description = null, room = "201", foundBy = "Bola", status = ItemStatus.DISPOSED),
        lfItem("d", name = "Deleted thing", room = "999", deleted = true)
    )

    @Test fun searchIsCaseInsensitiveOverNameDescriptionRoomAndFoundBy() {
        assertEquals(listOf("a"), filterLostFound(sample, "WATCH", null).map { it.id })
        assertEquals(listOf("a"), filterLostFound(sample, "leather", null).map { it.id })
        assertEquals(listOf("a", "c"), filterLostFound(sample, "201", null).map { it.id })
        assertEquals(listOf("b"), filterLostFound(sample, "chidi", null).map { it.id })
    }

    @Test fun blankSearchKeepsEverythingExceptDeleted() {
        assertEquals(listOf("a", "b", "c"), filterLostFound(sample, "   ", null).map { it.id })
    }

    @Test fun statusFilterAndSearchWorkTogether() {
        assertEquals(listOf("b"), filterLostFound(sample, "", ItemStatus.CLAIMED).map { it.id })
        assertEquals(listOf("c"), filterLostFound(sample, "201", ItemStatus.DISPOSED).map { it.id })
        assertTrue(filterLostFound(sample, "watch", ItemStatus.CLAIMED).isEmpty())
    }

    // ── detail sheet ──

    @Test fun markButtonsExcludeTheCurrentStatus() {
        ItemStatus.entries.forEach { current ->
            val targets = lostFoundMarkTargets(current)
            assertEquals(3, targets.size)
            assertFalse(current in targets)
        }
        assertEquals(
            listOf(ItemStatus.RETURNED_TO_GUEST, ItemStatus.CLAIMED, ItemStatus.DISPOSED),
            lostFoundMarkTargets(ItemStatus.STORED)
        )
        assertEquals("Mark Returned to Guest", lostFoundMarkLabel(ItemStatus.RETURNED_TO_GUEST))
    }

    @Test fun historyIsNewestFirst() {
        val first = lfHistory(ItemStatus.STORED, Instant.parse("2026-10-01T10:00:00Z"), note = "Item logged")
        val second = lfHistory(ItemStatus.CLAIMED, Instant.parse("2026-10-03T10:00:00Z"))
        val third = lfHistory(ItemStatus.DISPOSED, Instant.parse("2026-10-02T10:00:00Z"))
        val unknown = lfHistory(ItemStatus.STORED, null)
        assertEquals(
            listOf(second, third, first, unknown),
            lostFoundHistoryNewestFirst(listOf(first, second, third, unknown))
        )
    }

    @Test fun historyTiesShowTheLaterSavedEntryFirst() {
        val at = Instant.parse("2026-10-01T10:00:00Z")
        val a = lfHistory(ItemStatus.STORED, at, by = "A")
        val b = lfHistory(ItemStatus.CLAIMED, at, by = "B")
        assertEquals(listOf(b, a), lostFoundHistoryNewestFirst(listOf(a, b)))
    }

    @Test fun historyLineNamesStatusAndPerson() {
        val line = lostFoundHistoryLine(lfHistory(ItemStatus.RETURNED_TO_GUEST, null, by = "Ada"))
        assertEquals("Returned to Guest by Ada · —", line)
    }

    // ── Log Found Item validation ──

    private val rooms = listOf(LostFoundRoomOption("r1", "201", "Deluxe Room"), LostFoundRoomOption("r2", "202", "Standard Room"))
    private val zone = "Africa/Lagos"

    private fun goodForm() = LogFoundForm(
        itemName = "  Wristwatch ", description = " Silver, leather strap ", roomId = "r1",
        foundDate = LocalDate(2026, 10, 9), foundTime = LocalTime(14, 30), foundBy = " Ada ", notes = "  ", photoUrl = " "
    )

    @Test fun roomOptionLabel() {
        assertEquals("Room 201 — Deluxe Room", rooms[0].label)
    }

    @Test fun missingDateComesFirstEvenWhenEverythingElseIsMissing() {
        val check = validateLogFound(LogFoundForm(), rooms, zone)
        assertEquals(LogFoundCheck.Toast("Missing Date/Time", "Please enter when the item was found."), check)
        val noTime = validateLogFound(goodForm().copy(foundTime = null), rooms, zone)
        assertEquals(LogFoundCheck.Toast("Missing Date/Time", "Please enter when the item was found."), noTime)
    }

    @Test fun missingRequiredFieldsComeBeforeDescription() {
        val expected = LogFoundCheck.Toast("Missing Details", "Item name, room, and housekeeper name are required.")
        assertEquals(expected, validateLogFound(goodForm().copy(itemName = "  ", description = ""), rooms, zone))
        assertEquals(expected, validateLogFound(goodForm().copy(roomId = null, manualRoom = " "), rooms, zone))
        assertEquals(expected, validateLogFound(goodForm().copy(foundBy = ""), rooms, zone))
    }

    @Test fun blankDescriptionIsTheLastCheck() {
        assertEquals(LogFoundCheck.DescriptionMissing, validateLogFound(goodForm().copy(description = "   "), rooms, zone))
    }

    @Test fun validFormIsTrimmedAndUsesTheChosenRoom() {
        val ready = validateLogFound(goodForm(), rooms, zone) as LogFoundCheck.Ready
        val input = ready.input
        assertEquals("Wristwatch", input.itemName)
        assertEquals("Silver, leather strap", input.description)
        assertEquals("r1", input.roomId)
        assertEquals("201", input.roomNumber)
        assertEquals("Ada", input.foundBy)
        assertEquals(ItemStatus.STORED, input.status)
        assertNull(input.notes)
        assertNull(input.photoUrl)
        // 14:30 in Lagos (UTC+1) is 13:30 UTC.
        assertEquals(Instant.parse("2026-10-09T13:30:00Z"), input.foundAt)
    }

    @Test fun typedRoomHasNoRoomId() {
        val form = goodForm().copy(roomId = null, manualRoom = " 305 ")
        val input = (validateLogFound(form, rooms, zone) as LogFoundCheck.Ready).input
        assertNull(input.roomId)
        assertEquals("305", input.roomNumber)
    }

    @Test fun aChosenRoomThatIsNotInTheListFallsBackToTheTypedNumber() {
        val form = goodForm().copy(roomId = "gone", manualRoom = "410")
        val input = (validateLogFound(form, rooms, zone) as LogFoundCheck.Ready).input
        assertNull(input.roomId)
        assertEquals("410", input.roomNumber)
    }

    @Test fun anUnknownTimeZoneFallsBackToLagos() {
        val input = (validateLogFound(goodForm(), rooms, "Nowhere/Land") as LogFoundCheck.Ready).input
        assertEquals(Instant.parse("2026-10-09T13:30:00Z"), input.foundAt)
    }

    // ── payloads ──

    private val input = LogFoundInput(
        itemName = "Wristwatch", description = "Silver, leather strap", roomId = "r1", roomNumber = "201",
        foundAt = Instant.parse("2026-10-09T13:30:00Z"), foundBy = "Ada", status = ItemStatus.STORED, notes = null, photoUrl = null
    )

    @Test fun createPayloadHasEveryField() {
        val p = buildLostFoundCreatePayload(input, "u1", "Ada", LF_NOW)
        assertEquals(
            setOf(
                "itemName", "description", "roomId", "roomNumber", "foundAt", "foundByName", "foundBy", "status", "notes",
                "photoUrl", "createdBy", "createdByName", "createdAt", "updatedBy", "updatedByName", "updatedAt",
                "statusHistory", "isDeleted"
            ),
            p.keys
        )
        assertEquals("Wristwatch", p["itemName"])
        assertEquals("r1", p["roomId"])
        assertEquals("stored", p["status"])
        assertEquals("u1", p["foundBy"])
        assertEquals("Ada", p["foundByName"])
        assertEquals(false, p["isDeleted"])
        assertTrue(p["createdAt"] === LostFoundServerTime)
        assertTrue(p["updatedAt"] === LostFoundServerTime)
        assertEquals("u1", p["updatedBy"])
        assertEquals("Ada", p["updatedByName"])
    }

    @Test fun createPayloadSavesEmptyOptionalsAsNullAndRoomIdNullWhenTyped() {
        val typed = input.copy(roomId = null, notes = "  ", photoUrl = "")
        val p = buildLostFoundCreatePayload(typed, "u1", "Ada", LF_NOW)
        assertNull(p["roomId"])
        assertNull(p["notes"])
        assertNull(p["photoUrl"])
        val trimmed = buildLostFoundCreatePayload(input.copy(notes = " Guest called ", photoUrl = " https://x/y.jpg "), "u1", "Ada", LF_NOW)
        assertEquals("Guest called", trimmed["notes"])
        assertEquals("https://x/y.jpg", trimmed["photoUrl"])
    }

    @Test fun createPayloadStartsTheHistoryWithItemLogged() {
        val p = buildLostFoundCreatePayload(input.copy(status = ItemStatus.CLAIMED), "u1", "Ada", LF_NOW)
        @Suppress("UNCHECKED_CAST")
        val history = p["statusHistory"] as List<Map<String, Any?>>
        assertEquals(1, history.size)
        assertEquals(
            mapOf("status" to "claimed", "changedBy" to "u1", "changedByName" to "Ada", "changedAt" to LF_NOW, "note" to "Item logged"),
            history[0]
        )
    }

    @Test fun statusPayloadWritesOnlyTheStatusFieldsAndAppendsHistory() {
        val p = buildLostFoundStatusPayload(ItemStatus.CLAIMED, " Guest called ", "u9", "Boss", LF_NOW)
        assertEquals(setOf("status", "updatedAt", "updatedBy", "updatedByName", "statusHistory"), p.keys)
        assertEquals("claimed", p["status"])
        assertEquals("u9", p["updatedBy"])
        assertEquals("Boss", p["updatedByName"])
        assertTrue(p["updatedAt"] === LostFoundServerTime)
        val append = p["statusHistory"] as LostFoundHistoryAppend
        assertEquals(
            mapOf("status" to "claimed", "changedBy" to "u9", "changedByName" to "Boss", "changedAt" to LF_NOW, "note" to "Guest called"),
            append.entry
        )
    }

    @Test fun emptyNoteIsSavedAsNull() {
        val append = buildLostFoundStatusPayload(ItemStatus.DISPOSED, "   ", "u9", "Boss", LF_NOW)["statusHistory"] as LostFoundHistoryAppend
        assertNull(append.entry["note"])
    }

    @Test fun auditActionNamesTheChange() {
        assertEquals("lost_found_status_changed:stored→claimed", lostFoundStatusAuditAction(ItemStatus.STORED, ItemStatus.CLAIMED))
    }

    @Test fun onlyReturnedAndClaimedSendTheClaimedAlert() {
        assertTrue(lostFoundNotifiesClaimed(ItemStatus.RETURNED_TO_GUEST))
        assertTrue(lostFoundNotifiesClaimed(ItemStatus.CLAIMED))
        assertFalse(lostFoundNotifiesClaimed(ItemStatus.STORED))
        assertFalse(lostFoundNotifiesClaimed(ItemStatus.DISPOSED))
    }

    // ── parsing ──

    @Test fun parserIsTolerantAndReadsOlderGuestName() {
        val item = parseLostFoundItem(
            "x",
            mapOf(
                "itemName" to "Ring", "roomNumber" to "12", "status" to "mystery", "guestName" to "Mr Okoro",
                "foundAt" to Instant.parse("2026-10-09T10:00:00Z"),
                "statusHistory" to listOf(
                    mapOf("status" to "stored", "changedByName" to "Ada", "changedAt" to Instant.parse("2026-10-09T10:00:00Z"), "note" to "Item logged"),
                    "not a map",
                    mapOf("changedByName" to "No status")
                )
            )
        )
        assertEquals("Ring", item.itemName)
        assertEquals(ItemStatus.STORED, item.status)
        assertEquals("Mr Okoro", item.guestName)
        assertNull(item.description)
        assertEquals(1, item.statusHistory.size)
        assertEquals("Item logged", item.statusHistory[0].note)
        assertFalse(item.isDeleted)
    }

    @Test fun parserNeverThrowsOnEmptyData() {
        val item = parseLostFoundItem("y", emptyMap())
        assertEquals("—", item.itemName)
        assertEquals(ItemStatus.STORED, item.status)
        assertTrue(item.statusHistory.isEmpty())
    }
}
