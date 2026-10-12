package com.westly.nbms.features.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.RowScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState

/**
 * Gym card for the dashboards: dumbbell icon, "Gym" heading, a "Reports →" link to `gym/reports`, and three figures
 * (Active Members, In Gym Now, Visits Today). Renders NOTHING when the GYM module is off or the role may not open
 * `gym/reports`. Owns its ViewModel.
 */
@Composable
fun GymOverviewCard(session: SessionState.SignedIn, modifier: Modifier = Modifier) {
    if (!gymOverviewVisible(session.user.role, session.modules)) return
    GymOverviewContent(modifier)
}

@Composable
private fun GymOverviewContent(modifier: Modifier) {
    val viewModel: GymOverviewViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val nbms = MaterialTheme.nbms

    SectionCard(
        title = "Gym",
        icon = Icons.Outlined.FitnessCenter,
        modifier = modifier,
        linkText = "Reports",
        onLink = viewModel::openReports
    ) {
        when (val s = state) {
            is GymOverviewState.Loading -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
            is GymOverviewState.Error -> Text(
                "We couldn't load gym figures.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is GymOverviewState.Ready -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Figure(s.summary.activeMembers.toString(), "Active Members", nbms.success)
                Figure(s.summary.inGymNow.toString(), "In Gym Now", nbms.info)
                Figure(s.summary.visitsToday.toString(), "Visits Today", MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun RowScope.Figure(value: String, label: String, colour: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = colour, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
