package com.westly.nbms.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.nbmsBrandTitleStyle
import com.westly.nbms.core.feature.DashboardProvider
import com.westly.nbms.core.feature.NavRules
import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ShellOverlay
import com.westly.nbms.core.session.SessionState

/**
 * PLAIN placeholder body. Phase 7 replaces this file with the real shell (drawer, top bar, route guard).
 * The signature is final (Appendix A.5.1). It simply lists the navigation labels the person may see.
 */
@Composable
fun StaffShell(
    session: SessionState.SignedIn,
    features: Set<NbmsFeature>,
    dashboards: Set<DashboardProvider>,
    overlays: Set<ShellOverlay>
) {
    val nav = remember(features, session.user.role, session.modules) {
        NavRules.visibleNav(features, session.user.role, session.modules)
    }
    val grouped = remember(nav) { nav.groupBy { it.group } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("NBMS", style = nbmsBrandTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
        Text(
            "${session.business.name} · ${session.user.role.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        grouped.forEach { (group, items) ->
            if (group != null) {
                Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            }
            items.forEach { item ->
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = if (group != null) 16.dp else 0.dp)
                )
            }
        }
    }
}
