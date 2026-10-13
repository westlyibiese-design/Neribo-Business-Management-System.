package com.westly.nbms.core.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.nbms
import kotlinx.coroutines.delay

private fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"

/**
 * A thin strip under the top bar that tells the truth about saving:
 * offline (and how many changes are waiting), syncing after the internet returns, or a change the server refused.
 * Nothing is shown when everything is saved and online.
 */
@Composable
fun SyncBanner(modifier: Modifier = Modifier) {
    val status by SyncTracker.status.collectAsStateWithLifecycle(initialValue = SyncStatus(true, 0, emptyList()))

    // "Syncing" only appears if it takes a moment, so normal quick saves do not flash the strip.
    var syncingVisible by remember { mutableStateOf(false) }
    val syncing = status.online && status.pending > 0
    LaunchedEffect(syncing) {
        if (syncing) {
            delay(1_500L)
            syncingVisible = true
        } else {
            syncingVisible = false
        }
    }

    val failure = status.failures.firstOrNull()
    val text: String? = when {
        failure != null ->
            "${changes(status.failures.size)} could not be saved (${failure.label}). Tap to dismiss."
        !status.online && status.pending > 0 ->
            "Offline - ${changes(status.pending)} saved on this phone, waiting to sync."
        !status.online ->
            "Offline - everything you do is saved on this phone and syncs when the internet returns."
        syncingVisible -> "Back online - syncing ${changes(status.pending)}..."
        else -> null
    }
    val isError = failure != null

    AnimatedVisibility(visible = text != null, modifier = modifier) {
        val bg = if (isError) MaterialTheme.nbms.destructiveContainer else MaterialTheme.nbms.warningContainer
        val fg = if (isError) MaterialTheme.nbms.onDestructiveContainer else MaterialTheme.nbms.onWarningContainer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .then(if (isError) Modifier.clickable { SyncTracker.dismissFailures() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text.orEmpty(),
                color = fg,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
