package com.westly.nbms.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role as A11yRole
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.feature.TopBarAction
import com.westly.nbms.core.session.SessionState

/**
 * 56dp top bar on the card colour with a bottom border: menu button (phones only), a spacer,
 * the features' [TopBarAction]s in order, then the outlined role badge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopBar(
    session: SessionState.SignedIn,
    actions: List<TopBarAction>,
    showMenuButton: Boolean,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showMenuButton) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = A11yRole.Button, onClick = onMenuClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        NbmsIcons.Menu,
                        contentDescription = "Open menu",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            actions.forEach { action ->
                key(action) { action.Content(session) }
            }
            NbmsBadge(
                text = session.user.role.label,
                tone = BadgeTone.Outline,
                modifier = Modifier.widthIn(max = 170.dp)
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}
