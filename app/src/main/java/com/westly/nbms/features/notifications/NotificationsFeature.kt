package com.westly.nbms.features.notifications

import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import javax.inject.Inject
import javax.inject.Singleton

/** The Notifications page: every role may open it, and it has no drawer item (the bell opens it). */
@Singleton
class NotificationsFeature @Inject constructor() : NbmsFeature {
    override val id: String = "notifications"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE) { _, session -> NotificationsScreen(session) }
    )

    override val nav: List<NavSpec> = emptyList()

    companion object {
        const val ROUTE = "notifications"
    }
}
