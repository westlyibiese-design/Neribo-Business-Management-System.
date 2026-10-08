package com.westly.nbms.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import com.westly.nbms.features.settings.models.DEFAULT_MAINTENANCE_TEXT
import com.westly.nbms.features.settings.models.DEFAULT_MAINTENANCE_TITLE
import com.westly.nbms.features.settings.models.MaintenanceSettings
import com.westly.nbms.features.settings.models.MaintenanceTarget

/** System Maintenance: take the public website and/or the staff app into maintenance mode. */
@Composable
fun SystemMaintenanceScreen(
    @Suppress("UNUSED_PARAMETER") session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: SystemMaintenanceViewModel = hiltViewModel()
) {
    val load by vm.load.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val returnError by vm.returnError.collectAsStateWithLifecycle()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(NbmsIcons.Construction, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            PageHeader(
                title = "System Maintenance",
                subtitle = "Take the public website and/or the staff app into maintenance mode. Changes apply immediately, without a new release.",
                modifier = Modifier.weight(1f)
            )
        }

        val f = form
        val saved = (load as? Resource.Success<MaintenanceSettings?>)?.data
        when {
            f != null -> Column(
                Modifier.fillMaxWidth().widthIn(max = 672.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(saved)
                ConfigurationCard(
                    f = f,
                    returnError = returnError,
                    dirty = isDirty(f, vm.savedForm(saved)),
                    saving = saving,
                    onEdit = vm::edit,
                    onSave = vm::save
                )
            }
            load is Resource.Error -> ErrorState("We couldn't load maintenance settings.", onRetry = vm::retry)
            else -> LoadingState()
        }
    }
}

@Composable
private fun StatusCard(saved: MaintenanceSettings?) {
    val nbms = MaterialTheme.nbms
    val target = safeTarget(saved?.target ?: MaintenanceTarget.NONE)
    val active = target != MaintenanceTarget.NONE
    val statusColor = if (active) nbms.onWarningContainer else nbms.success
    val changedBy = saved?.updatedByName?.takeIf { it.isNotBlank() }
    val changedAt = saved?.updatedAt?.toInstant()

    NbmsCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (active) NbmsIcons.Construction else NbmsIcons.CheckCircle,
                contentDescription = null,
                tint = if (active) nbms.warning else nbms.success,
                modifier = Modifier.size(28.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text("Current status: ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        targetLabel(target),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = statusColor
                    )
                }
                if (changedBy != null && changedAt != null) {
                    Text(
                        "Last changed by $changedBy · ${Format.dateTime(changedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (active) NbmsBadge("Maintenance active", BadgeTone.Destructive)
            else NbmsBadge("All systems normal", BadgeTone.Outline)
        }
    }
}

@Composable
private fun ConfigurationCard(
    f: MaintenanceForm,
    returnError: String?,
    dirty: Boolean,
    saving: Boolean,
    onEdit: ((MaintenanceForm) -> MaintenanceForm) -> Unit,
    onSave: () -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Configuration",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Target", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                MaintenanceTarget.all.forEach { t ->
                    TargetRow(
                        selected = f.target == t,
                        icon = targetIcon(t),
                        label = targetLabel(t),
                        description = targetDescription(t),
                        onClick = { onEdit { it.copy(target = t) } }
                    )
                }
            }

            NbmsTextField(
                value = f.title,
                onValueChange = { v -> onEdit { it.copy(title = v.take(MAINTENANCE_TITLE_MAX)) } },
                label = "Maintenance Title",
                modifier = Modifier.fillMaxWidth(),
                placeholder = DEFAULT_MAINTENANCE_TITLE
            )
            NbmsTextField(
                value = f.message,
                onValueChange = { v -> onEdit { it.copy(message = v) } },
                label = "Maintenance Message",
                modifier = Modifier.fillMaxWidth(),
                placeholder = DEFAULT_MAINTENANCE_TEXT,
                singleLine = false
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Estimated Return Time (optional)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NbmsDatePickerField(
                        label = "", value = f.returnDate,
                        onChange = { d -> onEdit { it.copy(returnDate = d) } },
                        modifier = Modifier.weight(1f)
                    )
                    NbmsTimePickerField(
                        label = "", value = f.returnTime,
                        onChange = { t -> onEdit { it.copy(returnTime = t) } },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (f.returnDate != null || f.returnTime != null) {
                    NbmsButton(
                        text = "Clear return time",
                        onClick = { onEdit { it.copy(returnDate = null, returnTime = null) } },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm
                    )
                }
                if (returnError != null) {
                    Text(returnError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Shown to visitors as \"Expected back: …\". Leave blank to hide it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            NbmsButton(
                text = if (saving) "Saving…" else "Save Maintenance Settings",
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = dirty && !saving,
                loading = saving
            )
        }
    }
}

private fun targetIcon(target: String): ImageVector = when (target) {
    MaintenanceTarget.PUBLIC -> NbmsIcons.Globe
    MaintenanceTarget.ADMIN -> NbmsIcons.UserCog
    MaintenanceTarget.BOTH -> NbmsIcons.Construction
    else -> NbmsIcons.CheckCircle
}

@Composable
private fun TargetRow(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) scheme.primary else MaterialTheme.nbms.cardBorder, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (selected) {
            Box { Icon(NbmsIcons.Check, contentDescription = "Selected", tint = scheme.primary, modifier = Modifier.size(18.dp)) }
        }
    }
}
