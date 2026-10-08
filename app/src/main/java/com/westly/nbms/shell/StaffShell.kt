package com.westly.nbms.shell

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ShellOverlay
import com.westly.nbms.core.feature.TopBarAction
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.launch

/**
 * The signed-in shell (Phase 7): navigation drawer, top bar, route guard, profile sheet, theme switch
 * and maintenance gate around the page. It only reads the features through the Hilt sets; it never imports one.
 * The overlays (inactivity logout, device lock...) wrap the whole shell so a locked phone hides the drawer too.
 */
@Composable
fun StaffShell(
    session: SessionState.SignedIn,
    features: Set<NbmsFeature>,
    dashboards: Set<DashboardProvider>,
    overlays: Set<ShellOverlay>,
    topBarActions: Set<TopBarAction>
) {
    val orderedOverlays = remember(overlays) { overlays.sortedBy { it.order } }
    ApplyOverlays(orderedOverlays, 0, session) {
        ShellScaffold(session, features, dashboards, topBarActions)
    }
}

/** Lowest `order` is the outermost wrapper. */
@Composable
private fun ApplyOverlays(
    overlays: List<ShellOverlay>,
    index: Int,
    session: SessionState.SignedIn,
    content: @Composable () -> Unit
) {
    if (index >= overlays.size) {
        content()
    } else {
        overlays[index].Wrap(session) { ApplyOverlays(overlays, index + 1, session, content) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShellScaffold(
    session: SessionState.SignedIn,
    features: Set<NbmsFeature>,
    dashboards: Set<DashboardProvider>,
    topBarActions: Set<TopBarAction>
) {
    val vm: ShellViewModel = hiltViewModel()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val role = session.user.role
    val modules = session.modules
    val registered = remember(features) { registeredRoutes(features) }
    val entries = remember(features, role, modules) { DrawerModel.build(features, role, modules) }
    val actions = remember(topBarActions) { topBarActions.sortedBy { it.order } }

    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val hasDevicePin by vm.hasDevicePin.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showProfile by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // Every way of opening a page goes through the same guard: unknown or forbidden routes land on the Dashboard.
    fun openGuarded(route: String, topLevel: Boolean) {
        val target = ShellGuard.resolve(route, role, modules, registered)
        when {
            target == DASHBOARD_ROUTE && route != DASHBOARD_ROUTE -> navController.goToDashboard()
            topLevel -> navController.openTopLevel(target)
            else -> navController.openPage(target)
        }
    }

    // Links from `ShellNavigator.open` (notifications and similar).
    LaunchedEffect(navController, registered, role, modules) {
        vm.linkRequests.collect { route -> openGuarded(route, topLevel = false) }
    }

    // Back: close the drawer first; on the Dashboard a second Back within 2 seconds exits.
    val exitGate = remember { DoubleBackExit() }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = currentRoute == DASHBOARD_ROUTE && !drawerState.isOpen) {
        if (exitGate.onBack(SystemClock.elapsedRealtime())) {
            context.findActivity()?.finish()
        } else {
            vm.toast.show("Press back again to exit", ToastType.Info)
        }
    }

    val onNavigate: (String) -> Unit = { route ->
        scope.launch { drawerState.close() }
        openGuarded(route, topLevel = true)
    }

    val scheme = MaterialTheme.colorScheme
    val rootBackground = scheme.surfaceVariant.copy(alpha = 0.3f).compositeOver(scheme.background)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(rootBackground)
    ) {
        val permanentDrawer = maxWidth >= 840.dp

        val main: @Composable () -> Unit = {
            ShellMain(
                session = session,
                features = features,
                dashboards = dashboards,
                actions = actions,
                navController = navController,
                showMenuButton = !permanentDrawer,
                onMenuClick = { scope.launch { drawerState.open() } }
            )
        }

        val drawer: @Composable (Boolean) -> Unit = { endBorder ->
            DrawerContent(
                session = session,
                entries = entries,
                currentRoute = currentRoute,
                showLock = hasDevicePin && !session.user.usesPin,
                showEndBorder = endBorder,
                onNavigate = onNavigate,
                onProfile = {
                    scope.launch { drawerState.close() }
                    showProfile = true
                },
                onLock = {
                    scope.launch { drawerState.close() }
                    vm.lockNow()
                },
                onSignOut = {
                    scope.launch { drawerState.close() }
                    vm.signOut()
                }
            )
        }

        if (permanentDrawer) {
            Row(Modifier.fillMaxSize()) {
                drawer(true)
                Column(Modifier.weight(1f)) { main() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = Color.Black.copy(alpha = 0.6f),
                drawerContent = { drawer(false) },
                content = { main() }
            )
        }
    }

    if (showProfile) {
        ProfileSheet(
            session = session,
            themeMode = themeMode,
            onThemeChange = vm::setTheme,
            onCopyCode = { code ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Business code", code))
                vm.toast.show("Business code copied", ToastType.Success)
            },
            onOpenDeviceSettings = {
                showProfile = false
                openGuarded("device-settings", topLevel = true)
            },
            onSignOut = {
                showProfile = false
                vm.signOut()
            },
            onDismiss = { showProfile = false }
        )
    }
}

/** Top bar plus the page area (inside the maintenance gate). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShellMain(
    session: SessionState.SignedIn,
    features: Set<NbmsFeature>,
    dashboards: Set<DashboardProvider>,
    actions: List<TopBarAction>,
    navController: NavHostController,
    showMenuButton: Boolean,
    onMenuClick: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(
            session = session,
            actions = actions,
            showMenuButton = showMenuButton,
            onMenuClick = onMenuClick
        )
        MaintenanceGate(
            session = session,
            modifier = Modifier
                .weight(1f)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        ) {
            ShellNavHost(
                navController = navController,
                session = session,
                features = features,
                dashboards = dashboards
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
