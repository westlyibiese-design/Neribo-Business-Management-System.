package com.westly.nbms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.westly.nbms.core.design.AnimatedLogoScreen
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsSnackbarHost
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.nbmsBrandTitleStyle
import com.westly.nbms.core.feature.AuthFeature
import com.westly.nbms.core.feature.AuthNavigator
import com.westly.nbms.core.feature.AuthScreenSpec
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ShellOverlay
import com.westly.nbms.core.feature.TopBarAction
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.shell.StaffShell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.westly.nbms.core.util.Branding

@HiltViewModel
class RootViewModel @Inject constructor(
    val sessionManager: SessionManager,
    val authFeatures: Set<@JvmSuppressWildcards AuthFeature>,
    val features: Set<@JvmSuppressWildcards NbmsFeature>,
    val dashboards: Set<@JvmSuppressWildcards DashboardProvider>,
    val overlays: Set<@JvmSuppressWildcards ShellOverlay>,
    val topBarActions: Set<@JvmSuppressWildcards TopBarAction>,
    val toast: ToastController
) : ViewModel() {

    val state = sessionManager.state

    fun signOut() {
        viewModelScope.launch { sessionManager.signOut() }
    }

    /** "Try again" after a network problem or when loading takes too long. */
    fun retry() {
        viewModelScope.launch { sessionManager.refresh() }
    }
}

/** The animated logo stays at least this long when the app opens, so the intro is not cut off. */
private const val MIN_LOGO_MS = 2_500L

/** After this long on the logo screen a "Try again" button appears. */
private const val STUCK_HINT_MS = 15_000L

/** The app root: loading screen, auth screens, no-access card, or the signed-in shell. */
@Composable
fun NbmsRoot(vm: RootViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    // The logo plays for a short minimum when the app opens, even if sign-in is ready sooner.
    var minLogoDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(MIN_LOGO_MS)
        minLogoDone = true
    }
    val loading = state is SessionState.Loading
    var showStuckHint by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        showStuckHint = false
        if (loading) {
            delay(STUCK_HINT_MS)
            showStuckHint = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val s = state
        if (s is SessionState.Loading || !minLogoDone) {
            AnimatedLogoScreen(footer = { if (showStuckHint && loading) StuckHint(onRetry = vm::retry) })
        } else when (s) {
            is SessionState.Loading -> Unit
            is SessionState.SignedOut -> AuthHost(vm.authFeatures.flatMap { it.screens })
            is SessionState.NoAccess -> NoAccessScreen(reason = s.reason, onRetry = vm::retry, onSignOut = vm::signOut)
            is SessionState.SignedIn -> StaffShell(
                session = s,
                features = vm.features,
                dashboards = vm.dashboards,
                overlays = vm.overlays,
                topBarActions = vm.topBarActions
            )
        }
        NbmsSnackbarHost(controller = vm.toast)
    }
}

/** Shown under the logo when starting takes unusually long. */
@Composable
private fun BoxScope.StuckHint(onRetry: () -> Unit) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Still connecting…",
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color(0xFFFFD940).copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        NbmsButton(text = "Try again", onClick = onRetry, variant = ButtonVariant.Gold, size = ButtonSize.Default)
    }
}

@Composable
private fun AuthHost(screens: List<AuthScreenSpec>) {
    if (screens.none { it.route == "auth/login" }) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(Branding.APP_NAME, style = nbmsBrandTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Sign-in is not available yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        return
    }

    val navController = rememberNavController()
    val navigator = remember(navController) {
        object : AuthNavigator {
            override fun go(route: String) {
                navController.navigate(route) { launchSingleTop = true }
            }

            override fun back() {
                // Never pop the first screen. A quick second tap on a back link would otherwise remove the
                // sign-in page itself, and an empty NavHost draws a blank screen.
                if (navController.previousBackStackEntry != null) navController.popBackStack()
            }
        }
    }
    NavHost(navController = navController, startDestination = "auth/login") {
        screens.forEach { spec ->
            composable(spec.route) { spec.content(navigator) }
        }
    }
}

@Composable
private fun NoAccessScreen(reason: String, onRetry: () -> Unit, onSignOut: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        NbmsCard(modifier = Modifier.widthIn(max = 384.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(scheme.error.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        NbmsIcons.ShieldOff,
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No admin access on this account",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                NbmsButton(
                    text = "Try again",
                    onClick = onRetry,
                    variant = ButtonVariant.Default,
                    size = ButtonSize.Default,
                    leadingIcon = NbmsIcons.Refresh
                )
                NbmsButton(
                    text = "Sign out",
                    onClick = onSignOut,
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Default,
                    leadingIcon = NbmsIcons.LogOut
                )
            }
        }
    }
}
