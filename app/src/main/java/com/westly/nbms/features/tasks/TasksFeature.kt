package com.westly.nbms.features.tasks

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/**
 * The tasks feature: both pages.
 *
 * - `my-tasks` "My Tasks" (no group, order 20): every role (Phase 26B).
 * - `tasks` "Task Assignment" (Operations, order 60): Super Admin, Manager, Operations Manager (Phase 26A).
 */
class TasksFeature @Inject constructor() : NbmsFeature {
    override val id: String = "tasks"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("tasks") { _, session -> TasksScreen(session) },
        ScreenSpec("my-tasks") { _, session -> MyTasksScreen(session) }
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
        ),
        NavSpec(
            route = "my-tasks",
            label = "My Tasks",
            icon = NbmsIcons.ClipboardCheck,
            group = null,
            order = 20,
            roles = null,
            module = null
        )
    )
}
