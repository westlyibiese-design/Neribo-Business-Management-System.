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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.shell.StaffShell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    val sessionManager: SessionManager,
    val authFeatures: Set<@JvmSuppressWildcards AuthFeature>,
    val features: Set<@JvmSuppressWildcards NbmsFeature>,
    val dashboards: Set<@JvmSuppressWildcards DashboardProvider>,
    val overlays: Set<@JvmSuppressWildcards ShellOverlay>,
    val toast: ToastController
) : ViewModel() {

    val state = sessionManager.state

    fun signOut() {
        viewModelScope.launch { sessionManager.signOut() }
    }
}

/** The app root: loading screen, auth screens, no-access card, or the signed-in shell. */
@Composable
fun NbmsRoot(vm: RootViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val s = state) {
            is SessionState.Loading -> LoadingScreen()
            is SessionState.SignedOut -> AuthHost(vm.authFeatures.flatMap { it.screens })
            is SessionState.NoAccess -> NoAccessScreen(reason = s.reason, onSignOut = vm::signOut)
            is SessionState.SignedIn -> StaffShell(
                session = s,
                features = vm.features,
                dashboards = vm.dashboards,
                overlays = vm.overlays
            )
        }
        NbmsSnackbarHost(controller = vm.toast)
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NBMS", style = nbmsBrandTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
        CircularProgressIndicator(
            modifier = Modifier
                .padding(top = 16.dp)
                .size(20.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp
        )
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
            Text("NBMS", style = nbmsBrandTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
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
                navController.popBackStack()
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
private fun NoAccessScreen(reason: String, onSignOut: () -> Unit) {
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
