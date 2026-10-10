package com.westly.nbms.features.gym

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsSwitch
import com.westly.nbms.core.design.NbmsTimePickerField
import kotlinx.datetime.LocalTime

private val DAY_NAME_WIDTH = 112.dp
private val HOURS_WIDE_WIDTH = 520.dp

/** Hours tab: seven days, each open (with open and close times) or closed. Save Hours is enabled only when something changed. */
@Composable
internal fun HoursTab(vm: GymContentViewModel, state: GymContentUiState) {
    val saved = state.content.hours
    var rows by remember(saved) { mutableStateOf(saved) }

    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= HOURS_WIDE_WIDTH
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEachIndexed { index, row ->
                        HoursRowEditor(
                            row = row,
                            wide = wide,
                            onClosed = { rows = GymFormRules.withClosed(rows, index, it) },
                            onOpenTime = { t -> rows = GymFormRules.withOpenTime(rows, index, t.hour, t.minute) },
                            onCloseTime = { t -> rows = GymFormRules.withCloseTime(rows, index, t.hour, t.minute) }
                        )
                    }
                }
            }
            NbmsButton(
                text = "Save Hours",
                onClick = { vm.saveHours(rows) },
                loading = state.saving,
                enabled = GymFormRules.hoursChanged(saved, rows) && !state.saving,
                leadingIcon = NbmsIcons.Check
            )
        }
    }
}

@Composable
private fun HoursRowEditor(
    row: HoursRow,
    wide: Boolean,
    onClosed: (Boolean) -> Unit,
    onOpenTime: (LocalTime) -> Unit,
    onCloseTime: (LocalTime) -> Unit
) {
    val open = !row.closed
    val dayAndSwitch: @Composable () -> Unit = {
        Text(
            row.day,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(DAY_NAME_WIDTH)
        )
        NbmsSwitch(checked = open, onCheckedChange = { onClosed(!it) }, label = if (open) "Open" else "Closed")
    }
    val times: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsTimePickerField(
                label = "",
                value = GymFormRules.parseTime(row.open),
                onChange = onOpenTime,
                modifier = Modifier.weight(1f)
            )
            Text("to", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NbmsTimePickerField(
                label = "",
                value = GymFormRules.parseTime(row.close),
                onChange = onCloseTime,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (wide) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            dayAndSwitch()
            if (open) times(Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                dayAndSwitch()
            }
            if (open) times(Modifier.fillMaxWidth())
        }
    }
}
