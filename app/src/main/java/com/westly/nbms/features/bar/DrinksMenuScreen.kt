package com.westly.nbms.features.bar

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ConfirmDialog
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsSwitch
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

/** The Drinks Menu page (`bar-menu`): add, edit, switch availability and delete bar drinks. */
@Composable
fun DrinksMenuScreen(session: SessionState.SignedIn) {
    val vm: DrinksDrinksViewModel = hiltViewModel()
    val view by vm.view.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val busyIds by vm.busyIds.collectAsStateWithLifecycle()

    val symbol = session.business.currencySymbol
    var filterKey by rememberSaveable { mutableStateOf(BAR_FILTER_ALL) }
    // Only one form can be open at a time: the new-item form, or the form of one item being edited.
    var showNew by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val items = (view as? DrinksView.Ready)?.items.orEmpty()
    val counts = drinkCategoryCounts(items)
    val visible = filterDrinks(items, filterKey)
    val formOpen = showNew || editingId != null

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DrinksHeader()

        DrinkCategoryChips(counts = counts, selected = filterKey, onSelect = { filterKey = it })

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Menu Items (${visible.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (!formOpen) {
                NbmsButton(
                    text = "Add Item",
                    onClick = { showNew = true },
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }

        if (showNew) {
            DrinkForm(
                editing = null,
                saving = saving,
                imageProvider = vm.imageProvider,
                currencySymbol = symbol,
                onSave = { form -> vm.add(form) { showNew = false } },
                onCancel = { if (!saving) showNew = false }
            )
        }

        when (val v = view) {
            is DrinksView.Loading -> LoadingRow()
            is DrinksView.Error -> LoadErrorBox(v.message, onReload = vm::reload)
            is DrinksView.Ready -> {
                if (visible.isEmpty()) {
                    Text(
                        drinksEmptyMessage(filterKey),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                    )
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        visible.forEach { item ->
                            if (editingId == item.id) {
                                DrinkForm(
                                    editing = item,
                                    saving = saving,
                                    imageProvider = vm.imageProvider,
                                    currencySymbol = symbol,
                                    onSave = { form -> vm.update(item.id, form) { editingId = null } },
                                    onCancel = { if (!saving) editingId = null }
                                )
                            } else {
                                DrinkItemCard(
                                    item = item,
                                    symbol = symbol,
                                    busy = item.id in busyIds,
                                    onAvailable = { vm.setAvailable(item, it) },
                                    onEdit = {
                                        showNew = false
                                        editingId = item.id
                                    },
                                    onDelete = { deleteId = item.id }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val deleting = deleteId?.let { id -> items.firstOrNull { it.id == id } }
    if (deleting != null) {
        ConfirmDialog(
            title = "Delete Drink?",
            message = "Are you sure you want to delete \"${deleting.name}\"? This will remove it from the bar sale screen immediately.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                deleteId = null
                vm.delete(deleting) { if (editingId == deleting.id) editingId = null }
            },
            onDismiss = { deleteId = null }
        )
    }
}

// ── header, chips, load states ──

@Composable
private fun DrinksHeader() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            NbmsIcons.Wine,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp).size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text("Bar Menu Management", style = nbmsPageTitleStyle(), color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Manage drinks — everything here appears on the bar sale screen immediately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrinkCategoryChips(counts: Map<String, Int>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DrinkChip("All (${counts[BAR_FILTER_ALL] ?: 0})", selected == BAR_FILTER_ALL) { onSelect(BAR_FILTER_ALL) }
        DrinkCategory.entries.forEach { c ->
            DrinkChip("${c.label} (${counts[c.key] ?: 0})", selected == c.key) { onSelect(c.key) }
        }
    }
}

/** Pill chip: selected = filled primary, others muted. */
@Composable
private fun DrinkChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) scheme.primary else scheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Text(
            "Loading menu…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Destructive alert box shown while the menu failed to load; saving is blocked until Reload works. */
@Composable
private fun LoadErrorBox(message: String, onReload: () -> Unit) {
    val destructive = MaterialTheme.nbms.destructive
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, destructive.copy(alpha = 0.5f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(NbmsIcons.AlertTriangle, contentDescription = null, tint = destructive, modifier = Modifier.padding(top = 2.dp).size(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = destructive, modifier = Modifier.weight(1f))
        }
        NbmsButton(
            text = "Reload",
            onClick = onReload,
            variant = ButtonVariant.Outline,
            size = ButtonSize.Sm,
            leadingIcon = NbmsIcons.Refresh
        )
    }
}

// ── item card ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrinkItemCard(
    item: DrinkItem,
    symbol: String,
    busy: Boolean,
    onAvailable: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Thumbnail(item)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.name.ifBlank { "—" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallBadge(item.category.label, border = MaterialTheme.nbms.badgeOutline, textColor = scheme.onBackground)
                    if (!item.available) {
                        SmallBadge("Unavailable", border = MaterialTheme.nbms.destructive, textColor = MaterialTheme.nbms.destructive)
                    }
                }
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    Format.currency(item.price, symbol),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.primary
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NbmsSwitch(checked = item.available, onCheckedChange = onAvailable, enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconAction(NbmsIcons.Pencil, "Edit ${item.name}", enabled = !busy, onClick = onEdit)
                    IconAction(NbmsIcons.Trash, "Delete ${item.name}", enabled = !busy, destructiveOnPress = true, onClick = onDelete)
                }
            }
        }
    }
}

/** 64dp rounded thumbnail: the drink photo, or an image-off icon on a muted square. */
@Composable
private fun Thumbnail(item: DrinkItem) {
    val shape = RoundedCornerShape(8.dp)
    if (item.image.isNotBlank()) {
        AsyncImage(
            model = item.image,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(shape)
        )
    } else {
        Box(Modifier.size(64.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.HideImage,
                contentDescription = "No photo",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** Small outline badge, 10sp. */
@Composable
private fun SmallBadge(text: String, border: Color, textColor: Color) {
    val shape = MaterialTheme.shapes.small
    Text(
        text = text,
        color = textColor,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** 36dp icon button; with [destructiveOnPress] the icon turns red while it is pressed (the delete button). */
@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructiveOnPress: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val tint = if (destructiveOnPress && pressed) MaterialTheme.nbms.destructive else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(36.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}
