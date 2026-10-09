package com.westly.nbms.features.laundry

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LaundryHistoryRulesTest {

    private val lagos: ZoneId = ZoneId.of("Africa/Lagos")
    private val october = LaundryHistoryFilters(month = "2026-10")

    private fun ids(rows: List<LaundryRequest>) = rows.map { it.id }

    // ── month ──

    @Test fun theMonthFilterKeepsOnlyRequestsOfThatMonthInTheBusinessTimeZone() {
        val rows = listOf(
            laundryHistoryRequestOf("oct", at = Instant.parse("2026-10-09T10:00:00Z")),
            laundryHistoryRequestOf("sep", at = Instant.parse("2026-09-15T10:00:00Z")),
            laundryHistoryRequestOf("nov", at = Instant.parse("2026-11-02T10:00:00Z"))
        )
        assertEquals(listOf("oct"), ids(filterLaundryHistory(rows, october, lagos)))
    }

    @Test fun theMonthIsJudgedInTheBusinessTimeZoneNotInUtc() {
        val rows = listOf(
            laundryHistoryRequestOf("lagosOct1", at = Instant.parse("2026-09-30T23:30:00Z")), // 00:30 on 1 Oct in Lagos
            laundryHistoryRequestOf("lagosSep30", at = Instant.parse("2026-09-30T22:59:00Z")) // 23:59 on 30 Sep in Lagos
        )
        assertEquals(listOf("lagosOct1"), ids(filterLaundryHistory(rows, october, lagos)))
        assertEquals(listOf("lagosSep30"), ids(filterLaundryHistory(rows, LaundryHistoryFilters(month = "2026-09"), lagos)))
    }

    @Test fun aClearedMonthShowsEveryRequestEvenOneWithoutADate() {
        val rows = listOf(
            laundryHistoryRequestOf("oct"),
            laundryHistoryRequestOf("sep", at = Instant.parse("2026-09-15T10:00:00Z")),
            laundryHistoryRequestOf("nodate", at = null)
        )
        assertEquals(3, filterLaundryHistory(rows, LaundryHistoryFilters(month = ""), lagos).size)
        assertEquals(3, filterLaundryHistory(rows, LaundryHistoryFilters(month = "   "), lagos).size)
    }

    @Test fun aRequestWithoutADateIsHiddenWhenAMonthIsChosen() {
        val rows = listOf(laundryHistoryRequestOf("nodate", at = null))
        assertTrue(filterLaundryHistory(rows, october, lagos).isEmpty())
    }

    // ── search ──

    @Test fun searchMatchesTheGuestName() {
        val rows = listOf(
            laundryHistoryRequestOf("a", guest = "Mr Okoro"),
            laundryHistoryRequestOf("b", guest = "Mrs Adeyemi")
        )
        assertEquals(listOf("a"), ids(filterLaundryHistory(rows, october.copy(search = "okoro"), lagos)))
    }

    @Test fun searchMatchesTheRoomNumber() {
        val rows = listOf(
            laundryHistoryRequestOf("a", room = "201"),
            laundryHistoryRequestOf("b", room = "305")
        )
        assertEquals(listOf("b"), ids(filterLaundryHistory(rows, october.copy(search = "305"), lagos)))
    }

    @Test fun searchMatchesTheValetNameIgnoringCase() {
        val rows = listOf(
            laundryHistoryRequestOf("a", valet = "Wale"),
            laundryHistoryRequestOf("b", valet = "Ngozi")
        )
        assertEquals(listOf("b"), ids(filterLaundryHistory(rows, october.copy(search = "NGOZ"), lagos)))
    }

    @Test fun searchIsTrimmedAndABlankSearchShowsEverything() {
        val rows = listOf(laundryHistoryRequestOf("a", guest = "Mr Okoro"), laundryHistoryRequestOf("b", guest = "Other"))
        assertEquals(listOf("a"), ids(filterLaundryHistory(rows, october.copy(search = "  okoro  "), lagos)))
        assertEquals(2, filterLaundryHistory(rows, october.copy(search = "   "), lagos).size)
    }

    @Test fun searchHandlesRequestsWithNoGuestOrRoom() {
        val rows = listOf(laundryHistoryRequestOf("a", guest = null, room = null, valet = "Wale"))
        assertTrue(filterLaundryHistory(rows, october.copy(search = "okoro"), lagos).isEmpty())
        assertEquals(1, filterLaundryHistory(rows, october.copy(search = "wale"), lagos).size)
    }

    // ── status ──

    @Test fun everyStatusFiltersOnItsOwnKey() {
        val rows = LaundryStatus.entries.map { laundryHistoryRequestOf(it.key, status = it) }
        LaundryStatus.entries.forEach { status ->
            val shown = filterLaundryHistory(rows, october.copy(status = status.key), lagos)
            assertEquals(listOf(status.key), ids(shown))
        }
    }

    @Test fun cancelledCanBeFilteredAndAllStatusShowsEverything() {
        val rows = listOf(
            laundryHistoryRequestOf("a", status = LaundryStatus.CANCELLED),
            laundryHistoryRequestOf("b", status = LaundryStatus.DELIVERED)
        )
        assertEquals(listOf("a"), ids(filterLaundryHistory(rows, october.copy(status = "cancelled"), lagos)))
        assertEquals(2, filterLaundryHistory(rows, october.copy(status = LAUNDRY_HISTORY_STATUS_ALL), lagos).size)
    }

    @Test fun theStatusDropdownListsAllStatusTheSixStepsAndCancelled() {
        val options = laundryHistoryStatusOptions()
        assertEquals(
            listOf("All Status", "Received", "Washing", "Drying", "Ironing", "Ready for Collection", "Delivered", "Cancelled"),
            options.map { it.second }
        )
        assertEquals("", options.first().first)
        assertEquals("ready", options.first { it.second == "Ready for Collection" }.first)
    }

    // ── combinations ──

    @Test fun monthSearchAndStatusWorkTogether() {
        val rows = listOf(
            laundryHistoryRequestOf("hit", guest = "Mr Okoro", status = LaundryStatus.WASHING),
            laundryHistoryRequestOf("wrongStatus", guest = "Mr Okoro", status = LaundryStatus.READY),
            laundryHistoryRequestOf("wrongName", guest = "Someone", status = LaundryStatus.WASHING),
            laundryHistoryRequestOf("wrongMonth", guest = "Mr Okoro", status = LaundryStatus.WASHING, at = Instant.parse("2026-08-01T10:00:00Z"))
        )
        val filters = LaundryHistoryFilters(month = "2026-10", search = "okoro", status = "washing")
        assertEquals(listOf("hit"), ids(filterLaundryHistory(rows, filters, lagos)))
    }

    @Test fun softDeletedRequestsAreNeverShown() {
        val rows = listOf(laundryHistoryRequestOf("a"), laundryHistoryRequestOf("gone", deleted = true))
        assertEquals(listOf("a"), ids(filterLaundryHistory(rows, LaundryHistoryFilters(month = ""), lagos)))
    }

    // ── order ──

    @Test fun newestComesFirstAndRequestsWithoutADateComeLast() {
        val rows = listOf(
            laundryHistoryRequestOf("old", at = Instant.parse("2026-10-01T10:00:00Z")),
            laundryHistoryRequestOf("nodate", at = null),
            laundryHistoryRequestOf("new", at = Instant.parse("2026-10-09T10:00:00Z")),
            laundryHistoryRequestOf("mid", at = Instant.parse("2026-10-05T10:00:00Z"))
        )
        assertEquals(listOf("new", "mid", "old", "nodate"), ids(filterLaundryHistory(rows, LaundryHistoryFilters(month = ""), lagos)))
    }

    @Test fun requestsWithTheSameTimeKeepAStableOrderById() {
        val rows = listOf(laundryHistoryRequestOf("b"), laundryHistoryRequestOf("a"))
        assertEquals(listOf("a", "b"), ids(filterLaundryHistory(rows, october, lagos)))
    }

    // ── figures ──

    @Test fun theSubtitleCountsRequestsAndAddsTheChargesLeavingCancelledOut() {
        val rows = listOf(
            laundryHistoryRequestOf("a", charge = 4000.0),
            laundryHistoryRequestOf("b", charge = 2500.5, status = LaundryStatus.DELIVERED),
            laundryHistoryRequestOf("c", charge = 9999.0, status = LaundryStatus.CANCELLED)
        )
        assertEquals(2, laundryHistoryCount(rows))
        assertEquals(6500.5, laundryHistoryTotal(rows), 0.0001)
        assertEquals("2 requests · ₦6,500.50", laundryHistorySubtitle(rows, "₦"))
    }

    @Test fun theSubtitleOfAnEmptyListIsZeroRequests() {
        assertEquals("0 requests · ₦0", laundryHistorySubtitle(emptyList(), "₦"))
    }

    @Test fun theTotalHasNoFloatingPointNoise() {
        val rows = listOf(laundryHistoryRequestOf("a", charge = 0.1), laundryHistoryRequestOf("b", charge = 0.2))
        assertEquals(0.3, laundryHistoryTotal(rows), 0.0)
        assertEquals("2 requests · ₦0.30", laundryHistorySubtitle(rows, "₦"))
    }

    @Test fun theSubtitleUsesTheBusinessCurrencySymbol() {
        val rows = listOf(laundryHistoryRequestOf("a", charge = 1000.0))
        assertEquals("1 requests · \$1,000", laundryHistorySubtitle(rows, "\$"))
    }

    // ── who sees what ──

    @Test fun onlyTheLaundryValetIsScopedToTheirOwnRequests() {
        assertEquals("u9", laundryHistoryScopeFor(Role.LAUNDRY_VALET, "u9"))
        Role.entries.filter { it != Role.LAUNDRY_VALET }.forEach { role ->
            assertNull(laundryHistoryScopeFor(role, "u9"))
        }
        assertNull(laundryHistoryScopeFor(null, "u9"))
    }

    // ── months ──

    @Test fun theCurrentMonthLabelAndOptionsAreBuiltInTheBusinessTimeZone() {
        assertEquals("2026-10", laundryHistoryCurrentMonth(lagos, LAUNDRY_HISTORY_NOW))
        assertEquals("2026-10", laundryHistoryCurrentMonth(lagos, Instant.parse("2026-09-30T23:30:00Z")))
        assertEquals("October 2026", laundryHistoryMonthLabel("2026-10"))
        assertEquals("not-a-month", laundryHistoryMonthLabel("not-a-month"))
        val options = laundryHistoryMonthOptions(lagos, LAUNDRY_HISTORY_NOW)
        assertEquals(25, options.size)
        assertEquals(listOf("", "2026-10", "2026-09"), options.take(3))
        assertEquals("2024-11", options.last())
    }

    @Test fun anUnknownTimeZoneFallsBackToLagos() {
        assertEquals(lagos, laundryHistoryZone("Not/AZone"))
        assertEquals(lagos, laundryHistoryZone(null))
        assertEquals(ZoneId.of("Europe/London"), laundryHistoryZone("Europe/London"))
    }

    // ── row texts ──

    @Test fun theItemsTextHasTheCountInBrackets() {
        assertEquals("2 shirts (2)", laundryHistoryItemsText(laundryHistoryRequestOf("a")))
        assertEquals("— (3)", laundryHistoryItemsText(laundryHistoryRequestOf("a", items = null, count = 3)))
    }

    @Test fun theRoomLineShowsOnlyWhenBothAGuestAndARoomExist() {
        assertEquals("Room 201", laundryHistoryRoomLine(laundryHistoryRequestOf("a")))
        assertNull(laundryHistoryRoomLine(laundryHistoryRequestOf("a", guest = null)))
        assertNull(laundryHistoryRoomLine(laundryHistoryRequestOf("a", room = null)))
    }

    @Test fun theDateTimeIsShownInTheBusinessTimeZone() {
        assertEquals("9 Oct 2026, 11:00", laundryHistoryDateTime(LAUNDRY_HISTORY_NOW, TimeZone.of("Africa/Lagos")))
        assertEquals("—", laundryHistoryDateTime(null, TimeZone.of("Africa/Lagos")))
    }

    // ── what the page shows ──

    @Test fun theViewFollowsTheResource() {
        val rows = listOf(laundryHistoryRequestOf("a"))
        assertEquals(LaundryHistoryView.Loading, laundryHistoryViewOf(Resource.Loading, october, lagos))
        assertEquals(
            LaundryHistoryView.Error("We couldn't load laundry history."),
            laundryHistoryViewOf(Resource.Error("boom"), october, lagos)
        )
        val ready = laundryHistoryViewOf(Resource.Success(rows), october, lagos) as LaundryHistoryView.Ready
        assertEquals(listOf("a"), ids(ready.rows))
    }
}
