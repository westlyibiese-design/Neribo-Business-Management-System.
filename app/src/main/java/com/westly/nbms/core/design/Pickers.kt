package com.westly.nbms.core.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.westly.nbms.core.util.Format
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** Tap to pick a calendar date. Shows "12 Mar 2025" when a date is chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NbmsDatePickerField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select date",
    error: String? = null,
    enabled: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    TapField(
        label = label,
        text = value?.let { Format.localDate(it) },
        placeholder = placeholder,
        onClick = { open = true },
        modifier = modifier,
        error = error,
        enabled = enabled,
        trailingIcon = Icons.Outlined.CalendarMonth
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                NbmsButton(
                    text = "OK",
                    onClick = {
                        val millis = state.selectedDateMillis
                        if (millis != null) {
                            onChange(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                        }
                        open = false
                    },
                    variant = ButtonVariant.Ghost,
                    enabled = state.selectedDateMillis != null
                )
            },
            dismissButton = {
                NbmsButton(text = "Cancel", onClick = { open = false }, variant = ButtonVariant.Ghost)
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/** Tap to pick a time of day (24-hour). Shows "14:30" when chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NbmsTimePickerField(
    label: String,
    value: LocalTime?,
    onChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select time",
    error: String? = null,
    enabled: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    TapField(
        label = label,
        text = value?.let { String.format(java.util.Locale.US, "%02d:%02d", it.hour, it.minute) },
        placeholder = placeholder,
        onClick = { open = true },
        modifier = modifier,
        error = error,
        enabled = enabled,
        trailingIcon = Icons.Outlined.Schedule
    )
    if (open) {
        val state = rememberTimePickerState(
            initialHour = value?.hour ?: 12,
            initialMinute = value?.minute ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.background,
            title = { Text(label.ifEmpty { "Select time" }, style = MaterialTheme.typography.titleLarge) },
            text = { TimePicker(state = state) },
            confirmButton = {
                NbmsButton(
                    text = "OK",
                    onClick = {
                        onChange(LocalTime(state.hour, state.minute))
                        open = false
                    },
                    variant = ButtonVariant.Ghost
                )
            },
            dismissButton = {
                NbmsButton(text = "Cancel", onClick = { open = false }, variant = ButtonVariant.Ghost)
            }
        )
    }
}
