package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MyHousekeepingRoleRoutingTest {

    @Test fun housekeepingRoleGetsMyHousekeeping() {
        assertEquals(HousekeepingRouteView.MY_HOUSEKEEPING, housekeepingRouteView(Role.HOUSEKEEPING))
    }

    @Test fun managementGetsTheOverview() {
        listOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER).forEach {
            assertEquals("$it", HousekeepingRouteView.OVERVIEW, housekeepingRouteView(it))
        }
    }

    @Test fun featureRegistersOnlyTheHousekeepingRoute() {
        val feature = HousekeepingFeature()
        assertEquals("housekeeping", feature.id)
        assertEquals(listOf("housekeeping"), feature.screens.map { it.route })
        val nav = feature.nav.single()
        assertEquals("housekeeping", nav.route)
        assertEquals("Overview", nav.label)
        assertEquals("Housekeeping", nav.group)
        assertEquals(90, nav.order)
        assertEquals(ModuleKey.HOUSEKEEPING, nav.module)
        assertEquals(setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.HOUSEKEEPING, Role.OPERATIONS_MANAGER), nav.roles)
    }

    @Test fun receptionistNeverSeesTheRoute() {
        val roles = HousekeepingFeature().nav.single().roles
        assertNotNull(roles)
        assertEquals(false, Role.RECEPTIONIST in roles!!)
        assertNull(HousekeepingAssignmentsFeature().nav.single().roles?.firstOrNull { it == Role.HOUSEKEEPING })
    }
}
