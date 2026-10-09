package com.westly.nbms.features.messages

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ScreenSpec
import javax.inject.Inject
import javax.inject.Singleton

/** The Messages inbox (`messages`) for super_admin, manager and receptionist, with an unread badge in the drawer. */
@Singleton
class MessagesFeature @Inject constructor(
    private val api: MessagesApi
) : NbmsFeature {
    override val id: String = "messages"

    override val screens: List<ScreenSpec> = listOf(
        ScreenSpec(route = ROUTE) { _, session -> MessagesScreen(session) }
    )

    override val nav: List<NavSpec> = listOf(
        NavSpec(
            route = ROUTE,
            label = "Messages",
            icon = NbmsIcons.Reviews, // RateReview = MessageSquareText in the icon map
            group = null,
            order = 30,
            roles = MESSAGE_ROLES,
            module = null,
            badge = { session ->
                if (session.user.role in MESSAGE_ROLES) {
                    // Starts the shared tracker the first time the badge shows, so the count is right before the screen is opened.
                    LaunchedEffect(session.user.businessId) { api.ensureTracking() }
                    val count by api.unreadCount.collectAsStateWithLifecycle()
                    if (count > 0) count else null
                } else {
                    null
                }
            }
        )
    )

    companion object {
        const val ROUTE = "messages"
    }
}
