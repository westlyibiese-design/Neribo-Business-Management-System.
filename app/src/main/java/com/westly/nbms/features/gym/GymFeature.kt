package com.westly.nbms.features.gym

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Gym operations of Phase 29: three routes in the Gym drawer group, module GYM, for Super Admin, Manager,
 * Operations Manager and Gym Staff. Reports (163) and Membership Packages (164) belong to Phase 30.
 */
@Singleton
class GymFeature @Inject constructor() : NbmsFeature {
    override val id: String = "gym"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_GYM_CHECKIN) { _, session -> GymCheckInScreen(session) },
        ScreenSpec(ROUTE_GYM_MEMBERS) { _, session -> GymMembersScreen(session) },
        ScreenSpec(ROUTE_GYM_ATTENDANCE) { _, session -> GymAttendanceScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(ROUTE_GYM_CHECKIN, "Check-In/Out", NbmsIcons.Dumbbell, "Gym", 160, GYM_ROLES, ModuleKey.GYM),
        NavSpec(ROUTE_GYM_MEMBERS, "Members", NbmsIcons.Users, "Gym", 161, GYM_ROLES, ModuleKey.GYM),
        NavSpec(ROUTE_GYM_ATTENDANCE, "Attendance", NbmsIcons.CalendarClock, "Gym", 162, GYM_ROLES, ModuleKey.GYM)
    )

    companion object {
        const val ROUTE_GYM_CHECKIN = "gym/checkin"
        const val ROUTE_GYM_MEMBERS = "gym/members"
        const val ROUTE_GYM_ATTENDANCE = "gym/attendance"
        val GYM_ROLES: Set<Role> = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER, Role.GYM_STAFF)
    }
}
