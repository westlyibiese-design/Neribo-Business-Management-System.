package com.westly.nbms.features.tasks

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/**
 * Phase 26A2: the Task Assignment page only.
 *
 * - `tasks` "Task Assignment" (Operations, order 60): Super Admin, Manager, Operations Manager.
 *
 * My Tasks (`my-tasks`) is added by Phase 26B, which replaces this file.
 */
class TasksFeature @Inject constructor() : NbmsFeature {
    override val id: String = "tasks"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("tasks") { _, session -> TasksScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "tasks",
            label = "Task Assignment",
            icon = NbmsIcons.ClipboardCheck,
            group = "Operations",
            order = 60,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.OPERATIONS_MANAGER),
            module = null
        )
    )
}
