package com.westly.nbms.features.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.FixedTokens
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.TapField
import com.westly.nbms.core.util.Format

internal const val ROOM_SEARCH_PLACEHOLDER = "Search rooms by number, name, type, or status…"
internal const val ROOM_SEARCH_LOADING = "Loading rooms…"
internal const val ROOM_SEARCH_EMPTY = "No rooms match your search."

/** Case-insensitive match on room number, name, type and status. A blank [query] keeps every room. */
internal fun filterRoomsByQuery(rooms: List<Room>, query: String): List<Room> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return rooms
    return rooms.filter { room ->
        listOf(room.number, room.name.orEmpty(), room.type, room.status, room.status.replace('_', ' '))
            .any { it.lowercase().contains(q) }
    }
}

/** "Room 101 — Standard Room (₦45,000/night)". */
internal fun roomSelectionText(room: Room): String =
    "Room ${room.number} — ${room.type} (${Format.currency(room.price)}/night)"

/** The status text in the small badge: the status in capitals, e.g. "AVAILABLE", "OUT OF SERVICE". */
internal fun roomBadgeText(status: String): String = status.replace('_', ' ').uppercase()

private val BadgeGreen = Color(0xFF16A34A)
private val BadgeRed = Color(0xFFDC2626)
private val BadgeAmber = Color(0xFFD97706)
private val BadgeSlate = Color(0xFF64748B)

private fun roomBadgeColor(status: String): Color = when (status.lowercase()) {
    "available" -> BadgeGreen
    "occupied" -> BadgeRed
    "cleaning" -> BadgeAmber
    else -> BadgeSlate // maintenance and everything else
}

/**
 * A searchable room picker. Shows the chosen room in a read-only field; tapping it opens a bottom sheet
 * with a search box and the list of rooms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSearchSelect(
    rooms: List<Room>,
    valueRoomId: String?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    placeholder: String? = null,
    enabled: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = rooms.firstOrNull { it.id == valueRoomId }

    TapField(
        label = "",
        text = if (loading) null else selected?.let(::roomSelectionText),
        placeholder = if (loading) ROOM_SEARCH_LOADING else placeholder ?: ROOM_SEARCH_PLACEHOLDER,
        onClick = { open = true },
        modifier = modifier,
        enabled = enabled && !loading,
        trailingIcon = Icons.Outlined.ExpandMore
    )

    if (open) {
        val close = {
            open = false
            query = ""
        }
        ModalBottomSheet(
            onDismissRequest = close,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
            scrimColor = FixedTokens.scrimDialog
        ) {
            val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
            val matches = remember(rooms, query) { filterRoomsByQuery(rooms, query) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SearchBar(value = query, onValueChange = { query = it }, placeholder = ROOM_SEARCH_PLACEHOLDER)
                if (matches.isEmpty()) {
                    Text(
                        ROOM_SEARCH_EMPTY,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxListHeight),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(matches, key = { it.id }) { room ->
                            RoomPickRow(
                                room = room,
                                selected = room.id == valueRoomId,
                                onClick = {
                                    onChange(room.id)
                                    close()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomPickRow(room: Room, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Room ${room.number}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface
            )
            Text(
                room.displayName(),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${Format.currency(room.price)}/night",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface
            )
            val color = roomBadgeColor(room.status)
            Text(
                roomBadgeText(room.status),
                color = color,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, color, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}
