package com.westly.nbms.shell

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.feature.NavRules
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.session.SessionState

/**
 * Route strings contain "/" (for example `gym/members`). They are registered exactly as written:
 * navigation-compose treats a route as a path, so `navigate("gym/members")` works with no encoding.
 */
@Composable
fun ShellNavHost(
    navController: NavHostController,
    session: SessionState.SignedIn,
    features: Set<NbmsFeature>,
    dashboards: Set<DashboardProvider>,
    modifier: Modifier = Modifier
) {
    val screens = remember(features) {
        features.flatMap { it.screens }
            .filter { it.route != DASHBOARD_ROUTE }
            .distinctBy { it.route }
    }

    NavHost(
        navController = navController,
        startDestination = DASHBOARD_ROUTE,
        modifier = modifier,
        enterTransition = { fadeIn(tween(150)) },
        exitTransition = { fadeOut(tween(150)) },
        popEnterTransition = { fadeIn(tween(150)) },
        popExitTransition = { fadeOut(tween(150)) }
    ) {
        composable(DASHBOARD_ROUTE) {
            GuardedPage(DASHBOARD_ROUTE, session, navController) {
                DashboardRoute(session, dashboards)
            }
        }
        screens.forEach { spec ->
            composable(spec.route) {
                GuardedPage(spec.route, session, navController) {
                    spec.content(spec.route, session)
                }
            }
        }
    }
}

/**
 * Route guard: the page is shown only if the role may open it and its module is on.
 * Otherwise the person is sent to the Dashboard with no back-stack entry left behind.
 */
@Composable
private fun GuardedPage(
    route: String,
    session: SessionState.SignedIn,
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    val allowed = NavRules.canOpen(route, session.user.role, session.modules)
    if (allowed) {
        PageContainer(content)
    } else {
        LaunchedEffect(route) { navController.goToDashboard() }
    }
}

/** Scrollable page area: 16dp padding (24dp from 600dp wide), content capped at 1600dp and centred. */
@Composable
private fun PageContainer(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val padding = if (maxWidth >= 600.dp) 24.dp else 16.dp
        Column(
            modifier = Modifier
                .widthIn(max = 1600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {
            content()
        }
    }
}

/** The first dashboard provider that supports the role; the welcome card until Phase 32 supplies them. */
@Composable
private fun DashboardRoute(session: SessionState.SignedIn, dashboards: Set<DashboardProvider>) {
    val provider = remember(dashboards, session.user.role) {
        dashboards.firstOrNull { it.supports(session.user.role) }
    }
    if (provider != null) provider.Content(session) else DashboardHost(session)
}

/** Dashboard with an empty back stack (used by the guard). */
internal fun NavHostController.goToDashboard() {
    navigate(DASHBOARD_ROUTE) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** Drawer / profile selection: one entry per top-level item, state saved and restored. */
internal fun NavHostController.openTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** A link from a notification or a button: pushes the page so Back returns to where the person was. */
internal fun NavHostController.openPage(route: String) {
    if (route == DASHBOARD_ROUTE) {
        openTopLevel(route)
    } else {
        navigate(route) { launchSingleTop = true }
    }
}
