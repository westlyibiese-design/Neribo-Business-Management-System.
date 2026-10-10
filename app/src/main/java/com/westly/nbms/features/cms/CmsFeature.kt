package com.westly.nbms.features.cms

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/** Phase 31A1 registers only the Facilities page (`facilities`, order 260, Super Admin and Manager). */
class CmsFeature @Inject constructor() : NbmsFeature {
    override val id = "cms"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("facilities") { _, session -> FacilitiesScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "facilities",
            label = "Facilities",
            icon = NbmsIcons.Building,
            group = null,
            order = 260,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER),
            module = null
        )
    )
}
