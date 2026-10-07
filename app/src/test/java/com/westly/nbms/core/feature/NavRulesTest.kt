package com.westly.nbms.core.feature

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRulesTest {

    private val none = emptySet<ModuleKey>()
    private val all = ModuleKey.entries.toSet()

    @Test fun dashboardIsAlwaysOpen() {
        Role.entries.forEach { assertTrue(NavRules.canOpen("dashboard", it, none)) }
    }

    @Test fun receptionistCannotOpenUsers() {
        assertFalse(NavRules.canOpen("users", Role.RECEPTIONIST, all))
        assertTrue(NavRules.canOpen("users", Role.SUPER_ADMIN, none))
    }

    @Test fun gymMembersNeedsGymEvenForSuperAdmin() {
        assertFalse(NavRules.canOpen("gym/members", Role.SUPER_ADMIN, none))
        assertTrue(NavRules.canOpen("gym/members", Role.SUPER_ADMIN, setOf(ModuleKey.GYM)))
        assertFalse(NavRules.canOpen("gym/members", Role.RECEPTIONIST, all))
    }

    @Test fun managerOpensGymCmsOnlyWhenGymEnabled() {
        assertFalse(NavRules.canOpen("gym-cms", Role.MANAGER, none))
        assertTrue(NavRules.canOpen("gym-cms", Role.MANAGER, setOf(ModuleKey.GYM)))
        assertFalse(NavRules.canOpen("gym-cms", Role.ACCOUNTANT, all))
    }

    @Test fun unknownRouteIsNeverAllowed() {
        assertFalse(NavRules.canOpen("developer-tools", Role.SUPER_ADMIN, all))
        assertFalse(NavRules.canOpen("", Role.SUPER_ADMIN, all))
    }

    @Test fun routesWithoutNavItemsAreInTheAllowlist() {
        assertTrue(NavRules.canOpen("roles", Role.SUPER_ADMIN, none))
        assertTrue(NavRules.canOpen("attendance/record", Role.RECEPTIONIST, none))
        assertTrue(NavRules.canOpen("notifications", Role.DRIVER, none))
        assertTrue(NavRules.canOpen("device-settings", Role.KITCHEN_STAFF, none))
    }

    @Test fun tableHasEveryRegistryRoute() {
        assertEquals(54, NavRules.routeRoles.size)
        assertEquals(NavRules.routeRoles.keys, NavRules.routeModule.keys)
        assertNull(NavRules.routeRoles["dashboard"])
        assertNull(NavRules.routeModule["rooms"])
        assertEquals(ModuleKey.BAR, NavRules.routeModule["bar-menu"])
    }

    @Test fun moduleRoutesNeedTheirModule() {
        assertTrue(NavRules.canOpen("orders/new", Role.WAITER, setOf(ModuleKey.RESTAURANT)))
        assertFalse(NavRules.canOpen("orders/new", Role.WAITER, none))
        assertTrue(NavRules.canOpen("sales/new", Role.STAFF, setOf(ModuleKey.SALES_POS)))
        assertFalse(NavRules.canOpen("sales/new", Role.MANAGER, all))
        assertTrue(NavRules.canOpen("housekeeping", Role.HOUSEKEEPING, setOf(ModuleKey.HOUSEKEEPING)))
    }

    private fun nav(route: String, order: Int, roles: Set<Role>?, module: ModuleKey?) =
        NavSpec(route, route, Icons.Outlined.Dashboard, null, order, roles, module)

    private fun feature(vararg specs: NavSpec) = object : NbmsFeature {
        override val id = "test"
        override val screens: List<ScreenSpec> = emptyList()
        override val nav: List<NavSpec> = specs.toList()
    }

    @Test fun visibleNavFiltersAndSorts() {
        val f1 = feature(
            nav("rooms", 40, setOf(Role.SUPER_ADMIN, Role.RECEPTIONIST), null),
            nav("dashboard", 10, null, null)
        )
        val f2 = feature(
            nav("gym/members", 161, setOf(Role.SUPER_ADMIN), ModuleKey.GYM),
            nav("users", 220, setOf(Role.SUPER_ADMIN), null)
        )
        val features = setOf<NbmsFeature>(f1, f2)

        val admin = NavRules.visibleNav(features, Role.SUPER_ADMIN, none).map { it.route }
        assertEquals(listOf("dashboard", "rooms", "users"), admin)

        val adminGym = NavRules.visibleNav(features, Role.SUPER_ADMIN, setOf(ModuleKey.GYM)).map { it.route }
        assertEquals(listOf("dashboard", "rooms", "gym/members", "users"), adminGym)

        val rec = NavRules.visibleNav(features, Role.RECEPTIONIST, all).map { it.route }
        assertEquals(listOf("dashboard", "rooms"), rec)
    }
}
