package com.westly.nbms.features.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

internal const val MY_TASKS_ROUTE = "my-tasks"

/** Roles that have no dashboard of their own and land on My Tasks. */
internal val MY_TASKS_FALLBACK_ROLES: Set<Role> = setOf(
    Role.MAINTENANCE_TECHNICIAN, Role.SECURITY_GUARD, Role.DRIVER, Role.RESTAURANT_ATTENDANT, Role.KITCHEN_STAFF
)

/** Small private helper: lets the composable reach [ShellNavigator] (the provider itself must keep its no-argument constructor). */
@HiltViewModel
class MyTasksFallbackViewModel @Inject constructor(
    private val navigator: ShellNavigator
) : ViewModel() {
    fun openMyTasks() = navigator.open(MY_TASKS_ROUTE)
}

/**
 * Phase 34A1: replaces Westly's redirect for roles without a dashboard (maintenance technician, security guard, driver,
 * restaurant attendant, kitchen staff). Shows a centred spinner and opens My Tasks once; the saved flag stops a
 * back-press from looping.
 */
class MyTasksFallbackProvider @Inject constructor() : DashboardProvider {

    override fun supports(role: Role): Boolean = role in MY_TASKS_FALLBACK_ROLES

    @Composable
    override fun Content(session: SessionState.SignedIn) {
        val viewModel: MyTasksFallbackViewModel = hiltViewModel()
        var opened by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(opened) {
            if (!opened) {
                opened = true
                viewModel.openMyTasks()
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
