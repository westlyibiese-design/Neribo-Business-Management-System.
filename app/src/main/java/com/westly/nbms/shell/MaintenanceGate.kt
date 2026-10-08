package com.westly.nbms.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionState

const val DEFAULT_MAINTENANCE_MESSAGE = "We're performing scheduled maintenance. Thank you for your patience."
const val MAINTENANCE_BANNER_TEXT =
    "Maintenance mode is active — other staff are seeing the maintenance page right now."

/**
 * Watches `business.maintenanceMode` (kept live by the session). Off: the page. On: the Super Admin sees the page
 * with a warning banner above it; every other role sees [MaintenanceScreen] instead (the drawer and top bar stay).
 */
@Composable
fun MaintenanceGate(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val business = session.business
    when {
        !business.maintenanceMode -> Box(modifier.fillMaxSize()) { content() }
        session.user.role == Role.SUPER_ADMIN -> Column(modifier.fillMaxSize()) {
            MaintenanceBanner()
            Box(Modifier.weight(1f)) { content() }
        }
        else -> MaintenanceScreen(
            businessName = business.name,
            message = business.maintenanceMessage,
            modifier = modifier
        )
    }
}

@Composable
private fun MaintenanceBanner() {
    val nbms = MaterialTheme.nbms
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(nbms.warningContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            NbmsIcons.AlertTriangle,
            contentDescription = null,
            tint = nbms.onWarningContainer,
            modifier = Modifier.size(18.dp)
        )
        Text(
            MAINTENANCE_BANNER_TEXT,
            style = MaterialTheme.typography.bodyMedium,
            color = nbms.onWarningContainer
        )
    }
}

/** "We'll Be Right Back": dark slate page with a soft orange wrench circle. */
@Composable
fun MaintenanceScreen(
    businessName: String,
    message: String?,
    modifier: Modifier = Modifier
) {
    val orange = Color(0xFFFB923C)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 448.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(orange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(NbmsIcons.Wrench, contentDescription = null, tint = orange, modifier = Modifier.size(28.dp))
                }
                Text(
                    "We'll Be Right Back",
                    color = Color(0xFFF8FAFC),
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp)
                )
                Text(
                    message?.takeIf { it.isNotBlank() } ?: DEFAULT_MAINTENANCE_MESSAGE,
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    businessName,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
        }
    }
}
