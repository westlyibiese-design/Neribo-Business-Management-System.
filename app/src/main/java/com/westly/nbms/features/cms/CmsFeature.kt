package com.westly.nbms.features.cms

import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import com.westly.nbms.core.rbac.Role
import javax.inject.Inject

/**
 * The website CMS feature, all four routes. Phase 31A registered Facilities (`facilities`, order 260), Gallery (`gallery`,
 * order 280) and Guest Reviews (`reviews`, order 290) for Super Admin and Manager; Phase 31B adds the Website CMS page
 * (`cms`, order 250) for Super Admin only. All are top-level and always on.
 */
class CmsFeature @Inject constructor() : NbmsFeature {
    override val id = "cms"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec("cms") { _, session -> WebsiteCmsScreen(session) },
        ScreenSpec("facilities") { _, session -> FacilitiesScreen(session) },
        ScreenSpec("gallery") { _, session -> GalleryScreen(session) },
        ScreenSpec("reviews") { _, session -> ReviewsScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = "cms",
            label = "Website CMS",
            icon = NbmsIcons.BookOpen,
            group = null,
            order = 250,
            roles = setOf(Role.SUPER_ADMIN),
            module = null
        ),
        NavSpec(
            route = "facilities",
            label = "Facilities",
            icon = NbmsIcons.Building,
            group = null,
            order = 260,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER),
            module = null
        ),
        NavSpec(
            route = "gallery",
            label = "Gallery",
            icon = NbmsIcons.Images,
            group = null,
            order = 280,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER),
            module = null
        ),
        NavSpec(
            route = "reviews",
            label = "Guest Reviews",
            icon = NbmsIcons.Reviews,
            group = null,
            order = 290,
            roles = setOf(Role.SUPER_ADMIN, Role.MANAGER),
            module = null
        )
    )
}
