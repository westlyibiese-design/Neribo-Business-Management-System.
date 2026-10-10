package com.westly.nbms.features.opslog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState

/** Maintenance: open room issue requests (most urgent first) and the five most recently closed ones. */
@Composable
fun MaintenanceScreen(session: SessionState.SignedIn) {
    val vm: MaintenanceViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val logState by vm.log.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val closing by vm.closing.collectAsStateWithLifecycle()
    val pinEnding by vm.pinEnding.collectAsStateWithLifecycle()

    val role = session.user.role
    val canLog = maintenanceCanLog(role)
    val canClose = maintenanceCanClose(role)
    val ready = view as? MaintenanceView.Ready

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MaintenanceHeader(openCount = ready?.open?.size ?: 0, canLog = canLog, onLog = vm::openLogSheet)

        when (val v = view) {
            is MaintenanceView.Loading -> LoadingState()
            is MaintenanceView.Error -> ErrorState(v.message, onRetry = vm::retry)
            is MaintenanceView.Ready -> {
                if (v.open.isEmpty()) {
                    NoOpenRequests()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        v.open.forEach { request ->
                            OpenRequestCard(
                                request = request,
                                canClose = canClose,
                                closing = request.id in closing,
                                onClose = { vm.close(request) }
                            )
                        }
                    }
                }
                if (v.recentlyClosed.isNotEmpty()) RecentlyClosed(v.recentlyClosed)
            }
        }
    }

    if (logState.open) {
        LogMaintenanceSheet(
            state = logState,
            rooms = rooms,
            onChange = vm::updateForm,
            onCancel = vm::closeLogSheet,
            onSubmit = vm::submit
        )
    }

    if (pinEnding) PinSessionEndingOverlay()
}

@Composable
private fun MaintenanceHeader(openCount: Int, canLog: Boolean, onLog: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("Maintenance", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(
                maintenanceSubtitle(openCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canLog) {
            NbmsButton(text = "Log Request", onClick = onLog, leadingIcon = NbmsIcons.Plus)
        }
    }
}

/** Green check circle and "No open maintenance requests". */
@Composable
private fun NoOpenRequests() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            NbmsIcons.CheckCircle, contentDescription = null,
            tint = MaterialTheme.nbms.success, modifier = Modifier.size(48.dp)
        )
        Text(
            MSG_MAINTENANCE_EMPTY, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OpenRequestCard(request: MaintenanceRequest, canClose: Boolean, closing: Boolean, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth()) {
        NbmsCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            maintenanceRoomLabel(request.roomNumber), fontSize = 14.sp, lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold, color = scheme.onSurface
                        )
                        MaintenancePriorityPill(request.priority)
                    }
                    Text(
                        request.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                        color = scheme.onSurface
                    )
                    request.description?.let {
                        Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurfaceVariant)
                    }
                    Text(
                        maintenanceReportedLine(request), fontSize = 12.sp, lineHeight = 16.sp,
                        color = scheme.onSurfaceVariant
                    )
                }
                if (canClose) {
                    NbmsButton(
                        text = if (closing) "Closing…" else "Close",
                        onClick = onClose,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        loading = closing,
                        enabled = !closing,
                        leadingIcon = if (closing) null else NbmsIcons.Check
                    )
                }
            }
        }
        // High and critical requests get a red-tinted border drawn over the card's own.
        if (request.priority.isUrgent) {
            Box(Modifier.matchParentSize().border(1.dp, scheme.error.copy(alpha = 0.5f), shape))
        }
    }
}

@Composable
private fun RecentlyClosed(closed: List<MaintenanceRequest>) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Recently Closed", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            color = scheme.onSurfaceVariant
        )
        closed.forEach { request ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    maintenanceRoomLabel(request.roomNumber), fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant
                )
                Text(
                    "— ${request.title}", fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                ClosedBadge()
            }
        }
    }
}

/** Green outlined "Closed" badge. */
@Composable
private fun ClosedBadge() {
    val green = MaterialTheme.nbms.success
    Text(
        "Closed", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = green, maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, green, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 2.dp)
    )
}

// ── priority look ──

/** Light text / background and dark text / background of one priority pill (Tailwind palette). */
private class PriorityLook(val lightBg: Long, val lightText: Long, val darkBg: Long, val darkText: Long)

private fun MaintenancePriority.look(): PriorityLook = when (this) {
    MaintenancePriority.LOW -> PriorityLook(0xFFDBEAFE, 0xFF1E40AF, 0x4D1E3A8A, 0xFF60A5FA)       // blue
    MaintenancePriority.MEDIUM -> PriorityLook(0xFFFEF9C3, 0xFF854D0E, 0x4D713F12, 0xFFFACC15)    // yellow
    MaintenancePriority.HIGH -> PriorityLook(0xFFFEE2E2, 0xFF991B1B, 0x4D7F1D1D, 0xFFF87171)      // red
    MaintenancePriority.CRITICAL -> PriorityLook(0xFFFECACA, 0xFF7F1D1D, 0x807F1D1D, 0xFFFCA5A5)  // deeper red
}

@Composable
private fun MaintenancePriority.pillColors(): PillColors {
    val l = look()
    return if (MaterialTheme.nbms.isDark) PillColors(Color(l.darkBg), Color(l.darkText))
    else PillColors(Color(l.lightBg), Color(l.lightText))
}

/** Priority pill; Critical is bold. */
@Composable
internal fun MaintenancePriorityPill(priority: MaintenancePriority, modifier: Modifier = Modifier) {
    val colors = priority.pillColors()
    Text(
        text = priority.label,
        color = colors.content,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = if (priority == MaintenancePriority.CRITICAL) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(colors.container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

// ── shared-device sign-out overlay (private to Maintenance) ──

/** Full-screen dim layer shown for 2.5 seconds after a PIN user logs a request, just before the session ends. */
@Composable
private fun PinSessionEndingOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text("Ending session for security…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}
