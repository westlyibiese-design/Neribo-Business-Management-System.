package com.westly.nbms.features.attendance

import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The register's pure state-building and text helpers. */
class AttendanceViewTest {

    private val today = "2026-10-09"
    private val filters = AttendanceFilters("", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))

    private fun ui(
        attendance: Resource<List<AttendanceRecord>>,
        users: Resource<List<AttendanceUser>> = Resource.Success(emptyList()),
        f: AttendanceFilters = filters
    ) = buildAttendanceUi(attendance, users, f, today, LAGOS)

    @Test fun loadingShowsASpinnerAndZeroNumbers() {
        val s = ui(Resource.Loading)
        assertEquals(AttendanceView.Loading, s.view)
        assertEquals(TodaySummary(0, 0, 0, 0), s.summary)
    }

    @Test fun anErrorShowsTheLoadMessage() {
        val s = ui(Resource.Error("x"))
        assertEquals(AttendanceView.Error("We couldn't load attendance records."), s.view)
    }

    @Test fun noRecordsIsReadyWithNoGroupsAndStaffTotalStillShows() {
        val s = ui(Resource.Success(emptyList()), Resource.Success(listOf(attUser("a"), attUser("b"), attUser("c", deleted = true))))
        assertEquals(AttendanceView.Ready(emptyList()), s.view)
        assertEquals(TodaySummary(0, 0, 0, 2), s.summary)
    }

    @Test fun totalStaffIsZeroWhenTheStaffListFailed() {
        assertEquals(0, ui(Resource.Success(emptyList()), Resource.Error("x")).summary.totalStaff)
    }

    @Test fun summaryUsesAllRecordsOfTodayEvenOutsideTheChosenRange() {
        val records = listOf(att("a", dateKey = today, status = "present"), att("b", dateKey = today, status = "absent"))
        val narrow = filters.copy(from = LocalDate.of(2026, 10, 1), to = LocalDate.of(2026, 10, 5))
        val s = ui(Resource.Success(records), f = narrow)
        assertEquals(1, s.summary.present)
        assertEquals(1, s.summary.absent)
        assertEquals(AttendanceView.Ready(emptyList()), s.view)
    }

    @Test fun registerGroupsFilteredRecordsNewestFirst() {
        val records = listOf(
            att("a", "Ada", "2026-10-02"), att("b", "Ben", "2026-10-09"), att("c", "Cleo", "2026-09-20"), att("d", "Dan", "2026-10-09")
        )
        val view = ui(Resource.Success(records)).view as AttendanceView.Ready
        assertEquals(listOf("2026-10-09", "2026-10-02"), view.groups.map { it.dateKey })
        assertEquals(listOf("Ben", "Dan"), view.groups[0].records.map { it.staffName })
    }

    @Test fun searchNarrowsTheRegister() {
        val records = listOf(att("a", "Ada", "2026-10-09"), att("b", "Ben", "2026-10-09"))
        val view = ui(Resource.Success(records), f = filters.copy(search = "be")).view as AttendanceView.Ready
        assertEquals(listOf("Ben"), view.groups.single().records.map { it.staffName })
    }

    // ── who may do what ──

    @Test fun onlyTheFourRolesMayViewTheRegister() {
        val allowed = Role.entries.filter { attendanceCanView(it) }.toSet()
        assertEquals(setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.RECEPTIONIST, Role.OPERATIONS_MANAGER), allowed)
    }

    @Test fun onlySuperAdminAndReceptionistMayRecord() {
        val allowed = Role.entries.filter { attendanceCanRecord(it) }.toSet()
        assertEquals(setOf(Role.SUPER_ADMIN, Role.RECEPTIONIST), allowed)
        assertFalse(attendanceCanRecord(Role.MANAGER))
        assertFalse(attendanceCanRecord(Role.OPERATIONS_MANAGER))
    }

    // ── text ──

    @Test fun dayLabelIsWeekdayMonthDayYear() {
        assertEquals("Friday, Oct 9, 2026", attendanceDayLabel("2026-10-09"))
        assertEquals("Thursday, Mar 5, 2026", attendanceDayLabel("2026-03-05"))
        assertEquals("not-a-date", attendanceDayLabel("not-a-date"))
    }

    @Test fun recordCountIsSingularForOne() {
        assertEquals("1 record", attendanceRecordCount(1))
        assertEquals("2 records", attendanceRecordCount(2))
    }

    @Test fun roleLabelUsesTheRoleNameOrADash() {
        assertEquals("Operations Manager", attendanceRoleLabel("operations_manager"))
        assertEquals("custom_role", attendanceRoleLabel("custom_role"))
        assertEquals("—", attendanceRoleLabel(null))
        assertEquals("—", attendanceRoleLabel(" "))
    }

    @Test fun emptyTimesAndNotesShowADash() {
        assertEquals("—", attendanceOrDash(null))
        assertEquals("—", attendanceOrDash(""))
        assertEquals("09:15", attendanceOrDash("09:15"))
        assertTrue(attendanceOrDash("Late bus").startsWith("Late"))
    }
}
