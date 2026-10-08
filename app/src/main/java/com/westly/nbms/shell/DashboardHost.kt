package com.westly.nbms.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/** Fallback dashboard shown until a role dashboard (Phase 32) is registered: a short welcome. */
@Composable
fun DashboardHost(session: SessionState.SignedIn, modifier: Modifier = Modifier) {
    val today = remember { Clock.System.now() }
    val zone = remember(session.business.timezone) {
        runCatching { TimeZone.of(session.business.timezone) }.getOrDefault(TimeZone.currentSystemDefault())
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PageHeader(
            title = "Dashboard",
            subtitle = "Welcome back, ${firstName(session.user.name)}"
        )
        NbmsCard(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.business.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        Format.date(today, zone),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                NbmsBadge(session.user.role.label, BadgeTone.Outline)
            }
        }
    }
}
