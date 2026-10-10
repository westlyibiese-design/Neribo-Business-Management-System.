package com.westly.nbms.features.attendance

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AttendanceRulesTest {

    // ── doc id and date keys ──

    @Test fun docIdJoinsStaffAndDateWithTwoUnderscores() {
        assertEquals("abc123__2026-10-09", AttendanceRules.attendanceDocId("abc123", "2026-10-09"))
    }

    @Test fun dateKeyOfPadsMonthAndDay() {
        assertEquals("2026-03-05", AttendanceRules.dateKeyOf(LocalDate.of(2026, 3, 5)))
    }

    @Test fun midnightOfIsTheStartOfThatDayInTheBusinessZone() {
        // Lagos is UTC+1 all year, so local midnight is 23:00 UTC the evening before.
        assertEquals(Instant.parse("2026-10-08T23:00:00Z"), AttendanceRules.midnightOf("2026-10-09", LAGOS))
        assertEquals(Instant.parse("2026-10-09T00:00:00Z"), AttendanceRules.midnightOf("2026-10-09", ZoneId.of("UTC")))
    }

    @Test fun recordDateKeyUsesTheStoredKey() {
        assertEquals("2026-10-09", AttendanceRules.recordDateKey(att("a", dateKey = "2026-10-09"), LAGOS))
    }

    @Test fun recordDateKeyStoredKeyBeatsTheDateTimestamp() {
        val r = att("a", dateKey = "2026-10-09", date = Timestamp(Instant.parse("2026-01-01T12:00:00Z").epochSecond, 0))
        assertEquals("2026-10-09", AttendanceRules.recordDateKey(r, LAGOS))
    }

    @Test fun legacyRecordWithoutKeyReadsTheDateInTheBusinessZone() {
        // 23:30 UTC on 8 Oct is already 00:30 on 9 Oct in Lagos.
        val r = att("a", dateKey = null, date = Timestamp(Instant.parse("2026-10-08T23:30:00Z").epochSecond, 0), id = "a__old")
        assertEquals("2026-10-09", AttendanceRules.recordDateKey(r, LAGOS))
        assertEquals("2026-10-08", AttendanceRules.recordDateKey(r, ZoneId.of("UTC")))
    }

    @Test fun blankKeyFallsBackToTheDate() {
        val r = att("a", dateKey = "  ", date = Timestamp(Instant.parse("2026-10-09T10:00:00Z").epochSecond, 0))
        assertEquals("2026-10-09", AttendanceRules.recordDateKey(r, LAGOS))
    }

    @Test fun recordWithNeitherKeyNorDateHasNoDay() {
        assertNull(AttendanceRules.recordDateKey(att("a", dateKey = null), LAGOS))
    }

    // ── staff list ──

    @Test fun activeStaffDropsDeletedAndSuspendedAndSortsByName() {
        val users = listOf(
            attUser("1", "zara"), attUser("2", "Ben"), attUser("3", "Cleo", status = "suspended"),
            attUser("4", "Dan", deleted = true), attUser("5", "adam")
        )
        assertEquals(listOf("adam", "Ben", "zara"), AttendanceRules.activeStaff(users).map { it.name })
    }

    // ── existing records of a day, seeding ──

    @Test fun existingForDateKeepsOnlyThatDayAndSkipsDeleted() {
        val records = listOf(
            att("a", dateKey = "2026-10-09"), att("b", dateKey = "2026-10-08"), att("c", dateKey = "2026-10-09", deleted = true)
        )
        assertEquals(setOf("a"), AttendanceRules.existingForDate(records, "2026-10-09", LAGOS).keys)
    }

    @Test fun existingForDateFindsLegacyRecords() {
        val legacy = att("a", dateKey = null, date = Timestamp(Instant.parse("2026-10-09T09:00:00Z").epochSecond, 0), id = "old1")
        assertEquals(setOf("a"), AttendanceRules.existingForDate(listOf(legacy), "2026-10-09", LAGOS).keys)
    }

    @Test fun existingForDatePrefersTheStandardIdOverAStrayDuplicate() {
        val stray = att("a", dateKey = "2026-10-09", status = "absent", id = "random-id")
        val standard = att("a", dateKey = "2026-10-09", status = "late")
        assertEquals("late", AttendanceRules.existingForDate(listOf(standard, stray), "2026-10-09", LAGOS)["a"]!!.status)
        assertEquals("late", AttendanceRules.existingForDate(listOf(stray, standard), "2026-10-09", LAGOS)["a"]!!.status)
    }

    @Test fun seedRowsOnlyFillsStaffWhoHaveARecord() {
        val staff = listOf(attUser("a"), attUser("b"))
        val existing = mapOf("a" to att("a", status = "late", clockIn = "09:15", clockOut = null, notes = "Traffic"))
        val rows = AttendanceRules.seedRows(staff, existing)
        assertEquals(setOf("a"), rows.keys)
        assertEquals(AttendanceRowState(AttendanceStatus.LATE, "09:15", "", "Traffic"), rows["a"])
    }

    @Test fun seedRowsIgnoresRecordsOfPeopleNotInTheStaffList() {
        val rows = AttendanceRules.seedRows(listOf(attUser("a")), mapOf("zz" to att("zz")))
        assertTrue(rows.isEmpty())
    }

    @Test fun seedRowsReadsAnUnknownStatusAsPresent() {
        val rows = AttendanceRules.seedRows(listOf(attUser("a")), mapOf("a" to att("a", status = "weird")))
        assertEquals(AttendanceStatus.PRESENT, rows["a"]!!.status)
    }

    // ── re-seed rule (no clobbering on realtime updates) ──

    @Test fun reseedWhenTheDateChanges() {
        assertTrue(AttendanceRules.shouldReseed("2026-10-08", "2026-10-09", attendanceLoadedBefore = true, attendanceLoadedNow = true))
    }

    @Test fun reseedOnTheVeryFirstDate() {
        assertTrue(AttendanceRules.shouldReseed(null, "2026-10-09", attendanceLoadedBefore = false, attendanceLoadedNow = false))
    }

    @Test fun reseedWhenAttendanceFinishesItsFirstLoad() {
        assertTrue(AttendanceRules.shouldReseed("2026-10-09", "2026-10-09", attendanceLoadedBefore = false, attendanceLoadedNow = true))
    }

    @Test fun neverReseedOnALaterRealtimeUpdate() {
        assertFalse(AttendanceRules.shouldReseed("2026-10-09", "2026-10-09", attendanceLoadedBefore = true, attendanceLoadedNow = true))
    }

    @Test fun noReseedWhileStillLoading() {
        assertFalse(AttendanceRules.shouldReseed("2026-10-09", "2026-10-09", attendanceLoadedBefore = false, attendanceLoadedNow = false))
    }

    // ── effective row ──

    @Test fun effectiveRowDefaultsToPresentWithNothingElse() {
        assertEquals(AttendanceRowState(AttendanceStatus.PRESENT, "", "", ""), AttendanceRules.effectiveRow(null, null))
    }

    @Test fun effectiveRowShowsTheSavedRecordWhenNothingWasEdited() {
        val saved = att("a", status = "late", clockIn = "09:30", notes = "Bus")
        assertEquals(AttendanceRowState(AttendanceStatus.LATE, "09:30", "", "Bus"), AttendanceRules.effectiveRow(saved, null))
    }

    @Test fun effectiveRowEditedBeatsTheSavedRecord() {
        val saved = att("a", status = "late", clockIn = "09:30")
        val edited = AttendanceRowState(AttendanceStatus.ABSENT, "", "", "Sick call")
        assertEquals(edited, AttendanceRules.effectiveRow(saved, edited))
    }

    @Test fun effectiveRowOverridesBeatEditedAndOnlyTouchTheirFields() {
        val edited = AttendanceRowState(AttendanceStatus.LATE, "09:30", "", "Bus")
        val row = AttendanceRules.effectiveRow(null, edited, AttendanceRowOverrides(clockOut = "17:00"))
        assertEquals(AttendanceRowState(AttendanceStatus.LATE, "09:30", "17:00", "Bus"), row)
    }

    @Test fun effectiveRowOverridesWorkOnTheDefaultRow() {
        val row = AttendanceRules.effectiveRow(null, null, AttendanceRowOverrides(status = AttendanceStatus.PRESENT, clockIn = "08:05"))
        assertEquals(AttendanceRowState(AttendanceStatus.PRESENT, "08:05", "", ""), row)
    }

    // ── audit, time, guard ──

    @Test fun auditActionIsRecordedForNewAndUpdatedForExisting() {
        assertEquals("attendance_recorded", AttendanceRules.auditAction(null))
        assertEquals("attendance_updated", AttendanceRules.auditAction(att("a")))
    }

    @Test fun nowHHmmUsesTheBusinessZoneAndPadsDigits() {
        val now = Instant.parse("2026-10-09T07:05:00Z")
        assertEquals("08:05", AttendanceRules.nowHHmm(now, LAGOS))
        assertEquals("07:05", AttendanceRules.nowHHmm(now, ZoneId.of("UTC")))
        assertEquals("00:30", AttendanceRules.nowHHmm(Instant.parse("2026-10-08T23:30:00Z"), LAGOS))
    }

    @Test fun canSaveOnlyWhenNothingFailedToLoad() {
        assertTrue(AttendanceRules.canSave(usersFailed = false, attendanceFailed = false))
        assertFalse(AttendanceRules.canSave(usersFailed = true, attendanceFailed = false))
        assertFalse(AttendanceRules.canSave(usersFailed = false, attendanceFailed = true))
        assertFalse(AttendanceRules.canSave(usersFailed = true, attendanceFailed = true))
    }

    // ── register: filter ──

    private val sample = listOf(
        att("a", "Ada Obi", "2026-10-01"), att("b", "Ben Eze", "2026-10-05"), att("c", "Cleo Ade", "2026-10-09"),
        att("d", "Dan Okoro", "2026-09-30"), att("e", "Eve Ade", "2026-10-09", deleted = true)
    )

    @Test fun filterKeepsTheInclusiveRangeAndDropsDeleted() {
        val r = AttendanceRules.filterRegister(sample, "2026-10-01", "2026-10-09", "", LAGOS)
        assertEquals(listOf("a", "b", "c"), r.map { it.staffId })
    }

    @Test fun filterRangeEndsAreInclusive() {
        val r = AttendanceRules.filterRegister(sample, "2026-10-05", "2026-10-05", "", LAGOS)
        assertEquals(listOf("b"), r.map { it.staffId })
    }

    @Test fun filterSearchMatchesNameIgnoringCaseAndSpaces() {
        val r = AttendanceRules.filterRegister(sample, "2026-01-01", "2026-12-31", "  ADE ", LAGOS)
        assertEquals(listOf("c"), r.map { it.staffId }) // "Eve Ade" is deleted
    }

    @Test fun filterUsesTheLegacyDateWhenThereIsNoKey() {
        val legacy = att("x", "Old Timer", dateKey = null, date = Timestamp(Instant.parse("2026-10-03T10:00:00Z").epochSecond, 0), id = "legacy")
        assertEquals(listOf("x"), AttendanceRules.filterRegister(listOf(legacy), "2026-10-01", "2026-10-05", "", LAGOS).map { it.staffId })
    }

    @Test fun filterDropsRecordsWithNoDay() {
        assertTrue(AttendanceRules.filterRegister(listOf(att("x", dateKey = null)), "2026-01-01", "2026-12-31", "", LAGOS).isEmpty())
    }

    @Test fun filterWithABlankBoundDoesNotLimitThatSide() {
        assertEquals(4, AttendanceRules.filterRegister(sample, "", "", "", LAGOS).size)
        assertEquals(listOf("c"), AttendanceRules.filterRegister(sample, "2026-10-06", "", "", LAGOS).map { it.staffId })
    }

    // ── register: grouping ──

    @Test fun groupsAreNewestDayFirstAndNamesAreSortedInsideADay() {
        val records = listOf(
            att("z", "Zed", "2026-10-08"), att("m", "mia", "2026-10-09"), att("a", "Adam", "2026-10-09"), att("k", "Kim", "2026-10-07")
        )
        val groups = AttendanceRules.groupByDate(records, LAGOS)
        assertEquals(listOf("2026-10-09", "2026-10-08", "2026-10-07"), groups.map { it.dateKey })
        assertEquals(listOf("Adam", "mia"), groups[0].records.map { it.staffName })
    }

    @Test fun groupingPutsLegacyRecordsOnTheirLocalDay() {
        val legacy = att("l", "Legacy", dateKey = null, date = Timestamp(Instant.parse("2026-10-08T23:30:00Z").epochSecond, 0), id = "leg")
        val groups = AttendanceRules.groupByDate(listOf(legacy, att("a", "Adam", "2026-10-09")), LAGOS)
        assertEquals(1, groups.size)
        assertEquals(listOf("Adam", "Legacy"), groups[0].records.map { it.staffName })
    }

    @Test fun groupingNothingGivesNothing() {
        assertTrue(AttendanceRules.groupByDate(emptyList(), LAGOS).isEmpty())
    }

    // ── register: today summary ──

    @Test fun summaryCountsOnlyTodaysNonDeletedRecordsByStatus() {
        val records = listOf(
            att("a", status = "present"), att("b", status = "present"), att("c", status = "absent"), att("d", status = "late"),
            att("e", status = "leave"), att("f", status = "half_day"), att("g", status = "present", deleted = true),
            att("h", status = "present", dateKey = "2026-10-08")
        )
        assertEquals(TodaySummary(present = 2, absent = 1, late = 1, totalStaff = 12), AttendanceRules.todaySummary(records, "2026-10-09", 12, LAGOS))
    }

    @Test fun summaryCountsLegacyRecordsOfToday() {
        val legacy = att("l", status = "absent", dateKey = null, date = Timestamp(Instant.parse("2026-10-09T08:00:00Z").epochSecond, 0), id = "leg")
        assertEquals(1, AttendanceRules.todaySummary(listOf(legacy), "2026-10-09", 3, LAGOS).absent)
    }

    @Test fun summaryOfNothingIsZerosWithTheStaffTotal() {
        assertEquals(TodaySummary(0, 0, 0, 7), AttendanceRules.todaySummary(emptyList(), "2026-10-09", 7, LAGOS))
    }

    // ── status keys ──

    @Test fun statusFromKeyReadsAllFiveAndDefaultsToPresent() {
        assertEquals(AttendanceStatus.HALF_DAY, AttendanceStatus.fromKey("half_day"))
        assertEquals(AttendanceStatus.LEAVE, AttendanceStatus.fromKey("leave"))
        assertEquals(AttendanceStatus.PRESENT, AttendanceStatus.fromKey("nonsense"))
        assertEquals(AttendanceStatus.PRESENT, AttendanceStatus.fromKey(null))
        assertEquals(listOf("Present", "Absent", "Late", "Leave", "Half Day"), AttendanceStatus.entries.map { it.label })
    }
}
