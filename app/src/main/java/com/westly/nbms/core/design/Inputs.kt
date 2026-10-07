package com.westly.nbms.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Text input: label above, 36dp field, 6dp radius, red outline and message below when [error] is set.
 * With [isPassword] the text is hidden and an eye button shows / hides it.
 * Use an empty [label] to hide the label.
 */
@Composable
fun NbmsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val shape = MaterialTheme.shapes.small
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    var revealed by remember { mutableStateOf(false) }
    val borderColor = when {
        error != null -> scheme.error
        focused -> nbms.ring
        else -> nbms.inputBorder
    }

    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            visualTransformation = if (isPassword && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
            interactionSource = source,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (singleLine) 36.dp else 60.dp)
                        .border(1.dp, borderColor, shape)
                        .padding(horizontal = 12.dp, vertical = if (singleLine) 4.dp else 8.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                    if (isPassword) {
                        Icon(
                            imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (revealed) "Hide password" else "Show password",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(role = Role.Button) { revealed = !revealed }
                        )
                    }
                    trailing?.invoke()
                }
            }
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** A read-only field that looks like an input and reacts to taps (used by the dropdown and pickers). */
@Composable
internal fun TapField(
    label: String,
    text: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val shape = MaterialTheme.shapes.small
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .heightIn(min = 36.dp)
                .clip(shape)
                .border(1.dp, if (error != null) scheme.error else nbms.inputBorder, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = text ?: placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = if (text == null) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (trailingIcon != null) {
                Icon(trailingIcon, contentDescription = null, tint = scheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            }
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = scheme.error, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Select / dropdown. The menu uses the popover colours with the selected row highlighted. */
@Composable
fun <T> NbmsDropdown(
    label: String,
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "Select…",
    error: String? = null,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        TapField(
            label = label,
            text = selected?.let(optionLabel),
            placeholder = placeholder,
            onClick = { expanded = true },
            error = error,
            enabled = enabled,
            trailingIcon = Icons.Outlined.ExpandMore
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(scheme.surface)
                .border(1.dp, nbms.popoverBorder, MaterialTheme.shapes.small)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            optionLabel(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) scheme.onSecondaryContainer else scheme.onSurface
                        )
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(16.dp)) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = if (isSelected) Modifier.background(scheme.secondaryContainer) else Modifier
                )
            }
        }
    }
}

/** Switch: 36x20dp track, 16dp thumb. */
@Composable
fun NbmsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val track by animateColorAsState(if (checked) scheme.primary else nbms.inputBorder, label = "switchTrack")
    val thumbOffset by animateDpAsState(if (checked) 18.dp else 2.dp, label = "switchThumb")

    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(20.dp)
                .clip(CircleShape)
                .background(track)
        ) {
            Box(
                Modifier
                    .padding(start = thumbOffset)
                    .align(Alignment.CenterStart)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(scheme.background)
            )
        }
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

/** Checkbox: 16dp box, 4dp radius, primary border; checked = filled primary with a check mark. */
@Composable
fun NbmsCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) scheme.primary else Color.Transparent)
                .border(1.dp, scheme.primary, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

/** A vertical group of radio buttons. */
@Composable
fun <T> NbmsRadioGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = option == selected, enabled = enabled, role = Role.RadioButton) { onSelect(option) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadioButton(
                    selected = option == selected,
                    onClick = null,
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = scheme.primary,
                        unselectedColor = scheme.onSurfaceVariant
                    )
                )
                Text(optionLabel(option), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            }
        }
    }
}

/** Pill-style tabs: muted track (36dp, 8dp radius), the active tab is a raised background-coloured pill. */
@Composable
fun NbmsSegmentedTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val triggerShape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, title ->
            val active = index == selected
            val source = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(if (active) Modifier.nbmsShadow(2.dp, triggerShape) else Modifier)
                    .clip(triggerShape)
                    .background(if (active) scheme.background else Color.Transparent)
                    .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(index) }
                    .stateOverlay(source, triggerShape)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) scheme.onBackground else scheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
