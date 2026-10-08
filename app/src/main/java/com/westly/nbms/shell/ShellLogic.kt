package com.westly.nbms.shell

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavRules
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role

/** The route every signed-in person can open and the page the guard falls back to. */
const val DASHBOARD_ROUTE = "dashboard"

/** Turns a Westly-style link into a bare route. Pure, so it can be unit tested. */
object ShellLinks {
    private val aliases = mapOf(
        "sales-history" to "sales/history",
        "device-security" to "device-settings"
    )

    /**
     * "/admin/bookings?tab=1" -> "bookings", "/rooms" -> "rooms", "sales-history" -> "sales/history".
     * An empty link or "/admin" becomes the dashboard.
     */
    fun toRoute(link: String): String {
        var s = link.trim().substringBefore('#').substringBefore('?')
        s = when {
            s.startsWith("/admin/") -> s.removePrefix("/admin/")
            s.startsWith("/") -> s.removePrefix("/")
            else -> s
        }
        s = s.trim('/')
        if (s.isEmpty() || s == "admin") return DASHBOARD_ROUTE
        return aliases[s] ?: s
    }
}

/** The route guard decision (Appendix A.5): role allowed AND module on AND the screen really exists. */
object ShellGuard {
    fun resolve(route: String, role: Role, modules: Set<ModuleKey>, registered: Set<String>): String =
        if (route in registered && NavRules.canOpen(route, role, modules)) route else DASHBOARD_ROUTE
}

/** Every route that has a screen: the dashboard plus all feature screens. */
fun registeredRoutes(features: Set<NbmsFeature>): Set<String> =
    features.flatMap { it.screens }.map { it.route }.toSet() + DASHBOARD_ROUTE

/** One row of the drawer: a plain item, or a collapsible group with at least one visible child. */
sealed interface DrawerEntry {
    val order: Int

    data class Leaf(val spec: NavSpec) : DrawerEntry {
        override val order: Int get() = spec.order
    }

    data class Group(val label: String, val children: List<NavSpec>) : DrawerEntry {
        override val order: Int get() = children.minOf { it.order }
        fun contains(route: String?): Boolean = route != null && children.any { it.route == route }
    }
}

object DrawerModel {

    /** The shell's own Dashboard row: route `dashboard`, order 10, every role. */
    val dashboardItem: NavSpec = NavSpec(
        route = DASHBOARD_ROUTE,
        label = "Dashboard",
        icon = NbmsIcons.Dashboard,
        group = null,
        order = 10,
        roles = null,
        module = null
    )

    /**
     * Builds the drawer from the features' nav items: only what this role may see with the modules that are on,
     * sorted by order, children under one group row, empty groups dropped. Rows the guard would refuse are not shown.
     */
    fun build(features: Set<NbmsFeature>, role: Role, modules: Set<ModuleKey>): List<DrawerEntry> {
        val visible = (NavRules.visibleNav(features, role, modules)
            .filter { it.route != DASHBOARD_ROUTE && NavRules.canOpen(it.route, role, modules) } + dashboardItem)
            .distinctBy { it.route }
            .sortedBy { it.order }

        val entries = mutableListOf<DrawerEntry>()
        visible.filter { it.group == null }.forEach { entries += DrawerEntry.Leaf(it) }
        visible.filter { it.group != null }
            .groupBy { it.group!! }
            .forEach { (label, children) -> entries += DrawerEntry.Group(label, children.sortedBy { it.order }) }
        return entries.sortedBy { it.order }
    }
}

/** "99+" cap. Null (nothing to show) for no count, zero or negative. */
fun badgeText(count: Int?): String? = when {
    count == null || count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/** First word of the name for "Welcome back, …". */
fun firstName(name: String): String =
    name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { "there" }

/** First letter, upper case, for the round avatar ("?" when there is no name). */
fun initialOf(name: String): String =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

/** Second Back press within [windowMs] on the dashboard exits the app. */
class DoubleBackExit(private val windowMs: Long = 2000L) {
    private var lastBackMs: Long? = null

    /** Returns true when the app should exit now. */
    fun onBack(nowMs: Long): Boolean {
        val last = lastBackMs
        return if (last != null && nowMs - last <= windowMs) {
            lastBackMs = null
            true
        } else {
            lastBackMs = nowMs
            false
        }
    }
}
