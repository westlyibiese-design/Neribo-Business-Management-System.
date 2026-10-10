package com.westly.nbms.features.tasks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.PillColors
import com.westly.nbms.core.design.nbms

/** Light and dark colours for one pill: Tailwind 100 background / 800 text, and 900 at 30% / 400 text in dark. */
internal class TaskPillSpec(val lightBg: Long, val lightFg: Long, val darkBg: Long, val darkFg: Long) {
    fun colors(dark: Boolean): PillColors =
        if (dark) PillColors(Color(0xFF000000 or darkBg).copy(alpha = 0.30f), Color(0xFF000000 or darkFg))
        else PillColors(Color(0xFF000000 or lightBg), Color(0xFF000000 or lightFg))
}

private val Slate = TaskPillSpec(0xF1F5F9, 0x334155, 0x1E293B, 0x94A3B8)
private val Blue = TaskPillSpec(0xDBEAFE, 0x1E40AF, 0x1E3A8A, 0x60A5FA)
private val Orange = TaskPillSpec(0xFFEDD5, 0x9A3412, 0x7C2D12, 0xFB923C)
private val Red = TaskPillSpec(0xFEE2E2, 0x991B1B, 0x7F1D1D, 0xF87171)
private val Amber = TaskPillSpec(0xFEF3C7, 0x92400E, 0x78350F, 0xFBBF24)
private val Green = TaskPillSpec(0xDCFCE7, 0x166534, 0x14532D, 0x4ADE80)

/** low slate, medium blue, high orange, urgent red. */
internal fun taskPrioritySpec(priority: TaskPriority): TaskPillSpec = when (priority) {
    TaskPriority.LOW -> Slate
    TaskPriority.MEDIUM -> Blue
    TaskPriority.HIGH -> Orange
    TaskPriority.URGENT -> Red
}

/** pending slate, accepted blue, in progress amber, completed green, cancelled red. */
internal fun taskStatusSpec(status: TaskStatus): TaskPillSpec = when (status) {
    TaskStatus.PENDING -> Slate
    TaskStatus.ACCEPTED -> Blue
    TaskStatus.IN_PROGRESS -> Amber
    TaskStatus.COMPLETED -> Green
    TaskStatus.CANCELLED -> Red
}

@Composable
fun TaskPriorityPill(priority: TaskPriority, modifier: Modifier = Modifier) {
    NbmsPill(text = priority.label, colors = taskPrioritySpec(priority).colors(MaterialTheme.nbms.isDark), modifier = modifier)
}

@Composable
fun TaskStatusPill(status: TaskStatus, modifier: Modifier = Modifier) {
    NbmsPill(text = status.label, colors = taskStatusSpec(status).colors(MaterialTheme.nbms.isDark), modifier = modifier)
}

/** Small outlined chip with the type's label (for example "Room Booking"). */
@Composable
fun TaskTypeChip(type: TaskType, modifier: Modifier = Modifier) {
    NbmsBadge(text = type.label, tone = BadgeTone.Outline, modifier = modifier)
}
