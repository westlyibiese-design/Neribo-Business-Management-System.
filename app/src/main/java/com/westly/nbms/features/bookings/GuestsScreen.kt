package com.westly.nbms.features.bookings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.Avatar
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.EmptyState
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.design.PagedList
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant

private val TABLET_WIDTH = 600.dp

/** Guests: every registered guest with search, and a View History button that opens the guest profile. */
@Composable
fun GuestsScreen(
    @Suppress("UNUSED_PARAMETER") session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: GuestsViewModel = hiltViewModel()
) {
    val data by vm.data.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    val loaded = (data as? Resource.Success)?.data
    val subtitle = loaded?.let { "${it.guests.size} registered guests" }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= TABLET_WIDTH
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PageHeader(title = "Guests", subtitle = subtitle)

            when (val r = data) {
                is Resource.Loading -> LoadingState()
                is Resource.Error -> ErrorState("We couldn't load guests.", onRetry = vm::retry)
                is Resource.Success -> {
                    SearchBar(value = query, onValueChange = { query = it }, placeholder = "Search by name, email, or phone…")
                    val visible = remember(r.data.guests, query) { filterGuests(r.data.guests, query) }
                    if (visible.isEmpty()) {
                        EmptyState(
                            icon = NbmsIcons.Users,
                            title = "No guests found",
                            message = if (r.data.guests.isEmpty()) "Guests appear here after their first stay is registered."
                            else "No guests match your search."
                        )
                    } else if (wide) {
                        GuestsTable(visible, onView = { selectedId = it.id })
                    } else {
                        PagedList(items = visible, key = { it.id }) { guest ->
                            GuestCard(guest, onView = { selectedId = guest.id })
                        }
                    }
                }
            }
        }
    }

    val guest = loaded?.guests?.firstOrNull { it.id == selectedId }
    if (guest != null && loaded != null) {
        GuestProfileDialog(
            guest = guest,
            stays = guestStays(guest, loaded.bookings),
            onDismiss = { selectedId = null }
        )
    }
}

// ---- Phone card -------------------------------------------------------------------------------------

@Composable
private fun GuestCard(guest: Guest, onView: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(name = guest.name, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        guest.name.ifBlank { "Guest" },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ContactLines(guest)
                }
                NbmsBadge(text = guest.totalStays.toString(), tone = BadgeTone.Outline)
            }
            LabeledLine("Nationality", guest.nationality.orDash())
            LabeledLine("First Visit", Format.date(guest.firstVisit.toInstant()))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                NbmsButton(
                    text = "View History",
                    onClick = onView,
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                    leadingIcon = NbmsIcons.History
                )
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ContactLines(guest: Guest) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        guest.email.orDash(),
        style = MaterialTheme.typography.bodySmall,
        color = muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Text(
        if (guest.phone.isNullOrBlank()) "—" else Format.phone(guest.phone),
        style = MaterialTheme.typography.bodySmall,
        color = muted,
        maxLines = 1
    )
}

// ---- Tablet table -----------------------------------------------------------------------------------

private const val W_NAME = 2.2f
private const val W_CONTACT = 2.4f
private const val W_NATIONALITY = 1.3f
private const val W_STAYS = 1.0f
private const val W_VISIT = 1.3f
private const val W_ACTION = 1.5f

@Composable
private fun GuestsTable(guests: List<Guest>, onView: (Guest) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderCell("Guest", W_NAME)
            HeaderCell("Contact", W_CONTACT)
            HeaderCell("Nationality", W_NATIONALITY)
            HeaderCell("Total Stays", W_STAYS)
            HeaderCell("First Visit", W_VISIT)
            HeaderCell("", W_ACTION)
        }
        HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
        PagedList(items = guests, key = { it.id }) { g ->
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(W_NAME),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Avatar(name = g.name, size = 32.dp)
                        Text(
                            g.name.ifBlank { "Guest" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(Modifier.weight(W_CONTACT)) { ContactLines(g) }
                    Text(
                        g.nationality.orDash(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(W_NATIONALITY)
                    )
                    Row(Modifier.weight(W_STAYS)) { NbmsBadge(text = g.totalStays.toString(), tone = BadgeTone.Outline) }
                    Text(
                        Format.date(g.firstVisit.toInstant()),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(W_VISIT)
                    )
                    Row(Modifier.weight(W_ACTION), horizontalArrangement = Arrangement.End) {
                        NbmsButton(
                            text = "View History",
                            onClick = { onView(g) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            leadingIcon = NbmsIcons.History
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight)
    )
}
