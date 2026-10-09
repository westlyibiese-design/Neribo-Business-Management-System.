package com.westly.nbms.features.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ConfirmDialog
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsSwitch
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.rbac.Role as StaffRole
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format

internal const val CONTACT_FOR_PRICING = "Contact for pricing"

/** "200 guests", or a dash when the venue has no capacity. */
internal fun venueCapacityText(capacity: Int?): String = if (capacity != null) "$capacity guests" else "—"

/** "₦250,000", or "Contact for pricing" when there is no price. */
internal fun venuePriceText(price: Double?, symbol: String): String =
    if (price != null) Format.currency(price, symbol) else CONTACT_FOR_PRICING

/** Venues: a card grid. Super Admin only (add, edit, delete, availability switch). */
@Composable
fun VenuesScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: VenuesViewModel = hiltViewModel()
) {
    val venues by vm.venues.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val isSuperAdmin = session.user.role == StaffRole.SUPER_ADMIN
    val symbol = session.business.currencySymbol

    var filter by rememberSaveable { mutableStateOf("all") }
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val loaded: List<Venue> = when (val r = venues) {
        is Resource.Success -> r.data
        else -> emptyList()
    }
    val subtitle = if (venues is Resource.Success) "${loaded.size} venues" else null
    val chips = remember {
        listOf(
            FilterChipItem("all", "All"),
            FilterChipItem("available", "Available"),
            FilterChipItem("unavailable", "Unavailable")
        )
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(title = "Venues", subtitle = subtitle) {
            if (isSuperAdmin) {
                NbmsButton(
                    text = "Add Venue",
                    onClick = { editingId = null; showForm = true },
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }

        when (val r = venues) {
            is Resource.Loading -> LoadingState()
            is Resource.Error -> ErrorState("We couldn't load venues.", onRetry = vm::retry)
            is Resource.Success -> {
                FilterChips(chips, filter, onSelect = { filter = it })
                val visible = remember(r.data, filter) { filterVenues(r.data, filter) }
                if (visible.isEmpty()) {
                    EmptyState(
                        icon = NbmsIcons.Landmark,
                        title = "No venues found",
                        message = if (r.data.isEmpty()) "Tap Add Venue to create the first one." else "No venues match this filter."
                    )
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = gridColumns(maxWidth.value)
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            visible.chunked(columns).forEach { rowVenues ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    rowVenues.forEach { venue ->
                                        Box(Modifier.weight(1f)) {
                                            VenueCard(
                                                venue = venue,
                                                symbol = symbol,
                                                canManage = isSuperAdmin,
                                                busy = venue.id in state.busyIds,
                                                onAvailable = { vm.setAvailable(venue, it) },
                                                onEdit = { editingId = venue.id; showForm = true },
                                                onDelete = { deleteId = venue.id }
                                            )
                                        }
                                    }
                                    repeat(columns - rowVenues.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForm && isSuperAdmin) {
        val editing = loaded.firstOrNull { it.id == editingId }
        if (editingId == null || editing != null) {
            VenueFormDialog(
                editing = editing,
                currencySymbol = symbol,
                saving = state.saving,
                imageProvider = vm.imageProvider,
                onDismiss = { showForm = false },
                onSave = { input -> vm.saveVenue(editing, input) { showForm = false } }
            )
        }
    }

    val toDelete = loaded.firstOrNull { it.id == deleteId }
    if (isSuperAdmin && toDelete != null) {
        ConfirmDialog(
            title = "Delete Venue ${toDelete.name}?",
            message = "This action cannot be undone.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                deleteId = null
                vm.deleteVenue(toDelete)
            },
            onDismiss = { deleteId = null }
        )
    }
}

@Composable
private fun VenueCard(
    venue: Venue,
    symbol: String,
    canManage: Boolean,
    busy: Boolean,
    onAvailable: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        val image = venue.images.firstOrNull { it.isNotBlank() }
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = venue.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(144.dp)
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(
                    venue.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (venue.size.isNotBlank()) {
                    Text(venue.size, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            if (venue.description.isNotBlank()) {
                Text(
                    venue.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    venuePriceText(venue.price, symbol),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (venue.price != null) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    venueCapacityText(venue.capacity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
            AmenityChips(venue.amenities)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                NbmsSwitch(
                    checked = venue.available,
                    onCheckedChange = onAvailable,
                    enabled = canManage && !busy,
                    label = if (venue.available) "Available for booking" else "Unavailable",
                    modifier = Modifier.weight(1f)
                )
                if (canManage) {
                    IconAction(NbmsIcons.Pencil, "Edit ${venue.name}", enabled = !busy, onClick = onEdit)
                    IconAction(
                        NbmsIcons.Trash, "Delete ${venue.name}",
                        enabled = !busy, busy = busy, destructive = true, onClick = onDelete
                    )
                }
            }
        }
    }
}
