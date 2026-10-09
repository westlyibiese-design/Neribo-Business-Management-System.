package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Room Assignments feature of Part 23A: ONE route in the Housekeeping drawer group, module HOUSEKEEPING.
 *
 * - `housekeeping/assignments` "Room Assignments" (order 91): Super Admin, Manager, Operations Manager
 *
 * The Overview route (`housekeeping`) belongs to Part 23B's own feature.
 */
@Singleton
class HousekeepingAssignmentsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "housekeeping-assignments"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(ROUTE_ROOM_ASSIGNMENTS) { _, session -> RoomAssignmentsScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_ROOM_ASSIGNMENTS,
            label = "Room Assignments",
            icon = NbmsIcons.Users,
            group = "Housekeeping",
            order = 91,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER),
            module = ModuleKey.HOUSEKEEPING
        )
    )

    companion object {
        const val ROUTE_ROOM_ASSIGNMENTS = "housekeeping/assignments"
    }
}
