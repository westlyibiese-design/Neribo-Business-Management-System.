package com.westly.nbms.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Shared dialog frame: 80% black scrim, 16dp side gutters, max 512dp wide, max 85% of the screen tall. */
@Composable
private fun DialogFrame(
    onDismiss: () -> Unit,
    dismissOnOutsideTap: Boolean,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnOutsideTap,
            usePlatformDefaultWidth = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.8f) }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Box(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 512.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .nbmsShadow(8.dp, MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.nbms.cardBorder, MaterialTheme.shapes.medium)
        ) {
            content()
        }
    }
}

/**
 * Form dialog. Buttons are stacked on a phone: confirm on top, cancel below.
 * Pass `onConfirm = null` to show a single "Close" style button only.
 */
@Composable
fun NbmsDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String = "Save",
    onConfirm: (() -> Unit)? = null,
    dismissText: String = "Cancel",
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    DialogFrame(onDismiss = onDismiss, dismissOnOutsideTap = true, modifier = modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onConfirm != null) {
                    NbmsButton(
                        text = confirmText,
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Default,
                        loading = loading
                    )
                }
                NbmsButton(
                    text = if (onConfirm == null) "Close" else dismissText,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline,
                    enabled = !loading
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Close",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(16.dp)
                .alpha(0.7f)
                .clickable(role = Role.Button, onClick = onDismiss)
        )
    }
}

/** Yes / No confirmation (AlertDialog pattern): no close X and no dismiss by tapping outside. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    DialogFrame(onDismiss = onDismiss, dismissOnOutsideTap = false, modifier = modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NbmsButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Default
                )
                NbmsButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline
                )
            }
        }
    }
}

/** Bottom sheet with an optional title. Scrim is 80% black. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NbmsBottomSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = FixedTokens.scrimDialog,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }
            content()
        }
    }
}
