package com.westly.nbms.features.attendance

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/**
 * Staff attendance.
 *
 * - `attendance` "Attendance" (no group, order 190) for Super Admin, Manager, Receptionist and Operations Manager:
 *   the read-only register (Phase 28A).
 * - `attendance/record` "Record Attendance" for Super Admin and Receptionist only (Phase 28B). It has no menu entry:
 *   the register's button opens it, and the footer button on that page goes back. As with `roles` and `notifications`,
 *   a page without a drawer item is just a [ScreenSpec]; who may open it is decided by the route guard
 *   (`NavRules`: Super Admin and Receptionist for `attendance/record`), and the database rules enforce it again.
 */
class AttendanceFeature @Inject constructor() : NbmsFeature {
    override val id: String = "attendance"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("attendance") { _, session -> AttendanceScreen(session) },
        ScreenSpec("attendance/record") { _, session -> RecordAttendanceScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "attendance",
            label = "Attendance",
            icon = NbmsIcons.ClipboardCheck,
            group = null,
            order = 190,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.RECEPTIONIST, Role.OPERATIONS_MANAGER),
            module = null
        )
    )
}
