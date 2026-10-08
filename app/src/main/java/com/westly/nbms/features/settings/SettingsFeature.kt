package com.westly.nbms.features.settings

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject
import javax.inject.Singleton

/** Settings (`settings`), System Maintenance (`system-maintenance`) and Deleted Records (`deleted-records`). Super Admin only. */
@Singleton
class SettingsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "settings"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE_SETTINGS) { _, session -> SettingsScreen(session) },
        ScreenSpec(route = ROUTE_MAINTENANCE) { _, session -> SystemMaintenanceScreen(session) },
        ScreenSpec(route = ROUTE_DELETED) { _, _ -> DeletedRecordsScreen() }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE_DELETED, label = "Deleted Records", icon = NbmsIcons.Archive,
            group = null, order = 240, roles = setOf(Role.SUPER_ADMIN), module = null
        ),
        NavSpec(
            route = ROUTE_SETTINGS, label = "Settings", icon = NbmsIcons.Settings,
            group = null, order = 310, roles = setOf(Role.SUPER_ADMIN), module = null
        ),
        NavSpec(
            route = ROUTE_MAINTENANCE, label = "System Maintenance", icon = NbmsIcons.Construction,
            group = null, order = 320, roles = setOf(Role.SUPER_ADMIN), module = null
        )
    )

    companion object {
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_MAINTENANCE = "system-maintenance"
        const val ROUTE_DELETED = "deleted-records"
    }
}
