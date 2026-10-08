package com.westly.nbms.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLogicTest {

    private val none = emptySet<ModuleKey>()
    private val gymOn = setOf(ModuleKey.GYM)

    private fun spec(
        route: String,
        label: String,
        order: Int,
        group: String? = null,
        roles: Set<Role>? = null,
        module: ModuleKey? = null
    ) = NavSpec(
        route = route, label = label, icon = Icons.Outlined.Dashboard,
        group = group, order = order, roles = roles, module = module
    )

    private fun feature(vararg items: NavSpec, screenRoutes: List<String> = items.map { it.route }) = object : NbmsFeature {
        override val id = "fake-${items.firstOrNull()?.route}"
        override val screens = screenRoutes.map { r -> ScreenSpec(r) { _, _ -> } }
        override val nav = items.toList()
    }

    private val sa = Role.SUPER_ADMIN
    private val mgr = Role.MANAGER
    private val ops = Role.OPERATIONS_MANAGER
    private val rec = Role.RECEPTIONIST

    private val rooms = spec("rooms", "Rooms", 40, roles = setOf(sa, mgr, ops, rec))
    private val venues = spec("venues", "Venues", 50, roles = setOf(sa))
    private val guests = spec("guests", "Guests", 80, roles = setOf(sa, mgr, rec))
    private val bookings = spec("bookings", "All Bookings", 70, "Bookings", setOf(sa, mgr, ops, rec))
    private val reservations = spec("room-reservations", "Room Reservations", 71, "Bookings", setOf(sa, rec))
    private val checkin = spec("checkin", "Check-In", 72, "Bookings", setOf(sa, rec))
    private val gymMembers = spec("gym/members", "Members", 161, "Gym", setOf(sa, mgr, ops, Role.GYM_STAFF), ModuleKey.GYM)
    private val device = spec("device-settings", "Device Settings", 300)

    private val features: Set<NbmsFeature> =
        setOf(feature(rooms, venues, guests), feature(bookings, reservations, checkin), feature(gymMembers), feature(device))

    private fun routes(entries: List<DrawerEntry>): List<String> = entries.map {
        when (it) {
            is DrawerEntry.Leaf -> it.spec.route
            is DrawerEntry.Group -> "group:" + it.label
        }
    }

    // ---- drawer filtering ----

    @Test fun roleFilteringHidesWhatTheRoleCannotOpen() {
        val receptionist = routes(DrawerModel.build(features, rec, none))
        assertTrue("rooms" in receptionist)
        assertTrue("guests" in receptionist)
        assertFalse("venues" in receptionist)
        val superAdmin = routes(DrawerModel.build(features, sa, none))
        assertTrue("venues" in superAdmin)
    }

    @Test fun moduleFilteringHidesGymWhenTheModuleIsOff() {
        assertFalse("group:Gym" in routes(DrawerModel.build(features, sa, none)))
        assertTrue("group:Gym" in routes(DrawerModel.build(features, sa, gymOn)))
    }

    @Test fun gymStaffWithModuleSeesGymGroupButNotAdminItems() {
        val r = routes(DrawerModel.build(features, Role.GYM_STAFF, gymOn))
        assertTrue("group:Gym" in r)
        assertFalse("rooms" in r)
        assertFalse("venues" in r)
    }

    @Test fun groupIsHiddenWhenNoChildIsVisible() {
        val accountant = routes(DrawerModel.build(features, Role.ACCOUNTANT, none))
        assertFalse("group:Bookings" in accountant)
        assertFalse("rooms" in accountant)
    }

    @Test fun groupShowsOnlyTheVisibleChildren() {
        val entries = DrawerModel.build(features, ops, none)
        val group = entries.filterIsInstance<DrawerEntry.Group>().single { it.label == "Bookings" }
        assertEquals(listOf("bookings"), group.children.map { it.route })
    }

    @Test fun entriesAreSortedByOrderAndGroupsSitAtTheirFirstChild() {
        val entries = DrawerModel.build(features, sa, gymOn)
        assertEquals(
            listOf("dashboard", "rooms", "venues", "group:Bookings", "guests", "group:Gym", "device-settings"),
            routes(entries)
        )
        val bookingsGroup = entries.filterIsInstance<DrawerEntry.Group>().first { it.label == "Bookings" }
        assertEquals(listOf(70, 71, 72), bookingsGroup.children.map { it.order })
    }

    @Test fun dashboardIsAlwaysThereExactlyOnce() {
        val withOwnDashboard = setOf(feature(spec("dashboard", "Home", 5)), feature(device))
        val r = routes(DrawerModel.build(withOwnDashboard, Role.DRIVER, none))
        assertEquals(listOf("dashboard", "device-settings"), r)
        val label = (DrawerModel.build(withOwnDashboard, Role.DRIVER, none).first() as DrawerEntry.Leaf).spec.label
        assertEquals("Dashboard", label)
    }

    @Test fun withOnlyPhases0To7TheSuperAdminSeesDashboardAndDeviceSettings() {
        val only = setOf(feature(device))
        assertEquals(listOf("dashboard", "device-settings"), routes(DrawerModel.build(only, sa, none)))
        assertEquals(listOf("dashboard", "device-settings"), routes(DrawerModel.build(only, rec, none)))
    }

    @Test fun anItemTheGuardWouldRefuseIsNeverShown() {
        // The feature forgot to restrict this item, but the registry says Super Admin only.
        val sloppy = setOf(feature(spec("users", "Users & Roles", 220, roles = null)))
        assertFalse("users" in routes(DrawerModel.build(sloppy, rec, none)))
        assertTrue("users" in routes(DrawerModel.build(sloppy, sa, none)))
    }

    @Test fun groupContainsItsChildRoutes() {
        val group = DrawerEntry.Group("Bookings", listOf(bookings, checkin))
        assertTrue(group.contains("checkin"))
        assertFalse(group.contains("guests"))
        assertFalse(group.contains(null))
    }

    // ---- badge ----

    @Test fun badgeTextShowsNothingForZeroAndCapsAt99() {
        assertNull(badgeText(null))
        assertNull(badgeText(0))
        assertNull(badgeText(-3))
        assertEquals("1", badgeText(1))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
        assertEquals("99+", badgeText(2500))
    }

    // ---- link parsing ----

    @Test fun linksLoseTheAdminPrefixAndQueryString() {
        assertEquals("bookings", ShellLinks.toRoute("/admin/bookings"))
        assertEquals("bookings", ShellLinks.toRoute("/admin/bookings?tab=pending&x=1"))
        assertEquals("rooms", ShellLinks.toRoute("/rooms"))
        assertEquals("rooms", ShellLinks.toRoute("rooms"))
        assertEquals("gym/members", ShellLinks.toRoute("/admin/gym/members#top"))
        assertEquals("gym/members", ShellLinks.toRoute("gym/members/"))
    }

    @Test fun linkAliasesAreMapped() {
        assertEquals("sales/history", ShellLinks.toRoute("sales-history"))
        assertEquals("sales/history", ShellLinks.toRoute("/admin/sales-history?from=x"))
        assertEquals("device-settings", ShellLinks.toRoute("/admin/device-security"))
    }

    @Test fun emptyOrAdminOnlyLinksGoToTheDashboard() {
        assertEquals("dashboard", ShellLinks.toRoute(""))
        assertEquals("dashboard", ShellLinks.toRoute("   "))
        assertEquals("dashboard", ShellLinks.toRoute("/"))
        assertEquals("dashboard", ShellLinks.toRoute("/admin"))
        assertEquals("dashboard", ShellLinks.toRoute("/admin/"))
    }

    // ---- guard ----

    private val registered = setOf("dashboard", "rooms", "venues", "gym/members", "device-settings")

    @Test fun guardLetsAllowedRoutesThrough() {
        assertEquals("rooms", ShellGuard.resolve("rooms", rec, none, registered))
        assertEquals("device-settings", ShellGuard.resolve("device-settings", Role.DRIVER, none, registered))
    }

    @Test fun guardSendsForbiddenRoutesToTheDashboard() {
        assertEquals("dashboard", ShellGuard.resolve("venues", rec, none, registered))
    }

    @Test fun guardSendsRoutesWhoseModuleIsOffToTheDashboard() {
        assertEquals("dashboard", ShellGuard.resolve("gym/members", sa, none, registered))
        assertEquals("gym/members", ShellGuard.resolve("gym/members", sa, gymOn, registered))
    }

    @Test fun guardSendsUnknownOrNotYetBuiltRoutesToTheDashboard() {
        assertEquals("dashboard", ShellGuard.resolve("nonsense", sa, none, registered))
        // Allowed by the registry but no screen exists yet.
        assertEquals("dashboard", ShellGuard.resolve("expenses", sa, none, registered))
    }

    @Test fun registeredRoutesAlwaysIncludeTheDashboard() {
        val r = registeredRoutes(features)
        assertTrue("dashboard" in r)
        assertTrue("rooms" in r)
        assertTrue("gym/members" in r)
    }

    // ---- small helpers ----

    @Test fun doubleBackExitsOnlyOnTheSecondPressInsideTwoSeconds() {
        val gate = DoubleBackExit()
        assertFalse(gate.onBack(10_000))
        assertTrue(gate.onBack(11_500))
        assertFalse(gate.onBack(12_000))
        assertFalse(gate.onBack(14_500))
        assertTrue(gate.onBack(15_000))
    }

    @Test fun firstNameAndInitial() {
        assertEquals("Ada", firstName("  Ada Obi  "))
        assertEquals("there", firstName("   "))
        assertEquals("A", initialOf("ada obi"))
        assertEquals("?", initialOf(""))
    }
}
