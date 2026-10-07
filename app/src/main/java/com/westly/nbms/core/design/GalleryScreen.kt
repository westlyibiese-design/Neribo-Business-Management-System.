package com.westly.nbms.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

private data class SampleItem(val id: Int, val title: String, val detail: String)

private val SampleRooms = listOf("Standard Room", "Deluxe Room", "Junior Suite", "Executive Suite", "Presidential Suite")

private fun prettyKey(key: String): String =
    key.split("_").joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }

/**
 * TEMPORARY screen (deleted in Phase 7). Shows every NBMS component so the look can be checked on a phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    toast: ToastController,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme

    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var room by remember { mutableStateOf<String?>(null) }
    var switchOn by rememberSaveable { mutableStateOf(true) }
    var checked by rememberSaveable { mutableStateOf(true) }
    var radio by remember { mutableStateOf<String?>("Cash") }
    var tab by rememberSaveable { mutableStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var loadingButton by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }

    val items = remember {
        (1..23).map { SampleItem(it, "Sample booking #$it", "Room ${100 + it} · ₦${45_000 + it * 500}") }
    }
    val filtered = remember(search, items) {
        if (search.isBlank()) items else items.filter { it.title.contains(search, ignoreCase = true) }
    }

    Box(modifier.fillMaxSize().background(scheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header and theme switch
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NBMS", style = nbmsBrandTitleStyle(), color = scheme.onBackground)
                Text("Component gallery (temporary)", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                NbmsSegmentedTabs(
                    tabs = listOf("Light", "Dark", "System"),
                    selected = themeMode.ordinal,
                    onSelect = { onThemeModeChange(ThemeMode.entries[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            PageHeader(title = "Page title", subtitle = "A short subtitle", actions = {
                NbmsButton("Add", onClick = { toast.show("Add tapped", ToastType.Info) }, size = ButtonSize.Sm, leadingIcon = NbmsIcons.Plus)
            })

            FormSection("Buttons") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonVariant.entries.forEach { v ->
                        NbmsButton(v.name, onClick = { toast.show("${v.name} button", ToastType.Info) }, variant = v)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NbmsButton("Small", onClick = {}, size = ButtonSize.Sm)
                    NbmsButton("Default", onClick = {})
                    NbmsButton("Large", onClick = {}, size = ButtonSize.Lg)
                    NbmsButton("Edit", onClick = {}, size = ButtonSize.Icon, variant = ButtonVariant.Outline, leadingIcon = NbmsIcons.Pencil)
                    NbmsButton("With icon", onClick = {}, leadingIcon = NbmsIcons.Plus)
                    NbmsButton("Disabled", onClick = {}, enabled = false)
                    NbmsButton(
                        if (loadingButton) "Saving…" else "Tap to load",
                        onClick = { loadingButton = !loadingButton },
                        loading = loadingButton
                    )
                }
                if (loadingButton) {
                    NbmsButton("Stop loading", onClick = { loadingButton = false }, variant = ButtonVariant.Ghost)
                }
            }

            FormSection("Text fields") {
                NbmsTextField(name, { name = it }, label = "Full name", placeholder = "Jane Doe")
                NbmsTextField(
                    email, { email = it }, label = "Email (error example)", placeholder = "name@example.com",
                    keyboardType = KeyboardType.Email,
                    error = "Enter a valid email address."
                )
                NbmsTextField(password, { password = it }, label = "Password", placeholder = "Min. 8 characters", isPassword = true)
                NbmsDropdown(
                    label = "Room type",
                    options = SampleRooms,
                    selected = room,
                    onSelect = { room = it },
                    optionLabel = { it },
                    placeholder = "Select a room type"
                )
            }

            FormSection("Switch, checkbox, radio, tabs") {
                NbmsSwitch(switchOn, { switchOn = it }, label = "Email notifications")
                NbmsCheckbox(checked, { checked = it }, label = "Remember this device")
                NbmsRadioGroup(
                    options = listOf("Cash", "Transfer", "Card"),
                    selected = radio,
                    onSelect = { radio = it },
                    optionLabel = { it }
                )
                NbmsSegmentedTabs(
                    tabs = listOf("Today", "Week", "Month"),
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FormSection("Badges") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BadgeTone.entries.forEach { NbmsBadge(it.name, tone = it) }
                }
            }

            FormSection("Role colours") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RolePillSpecs.keys.forEach { key ->
                        NbmsPill(prettyKey(key), MaterialTheme.nbms.rolePill(key))
                    }
                }
            }

            FormSection("Status colours") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("available", "occupied", "cleaning", "reserved", "maintenance", "out_of_service", "pending", "checked_in", "checked_out", "cancelled", "no_show")
                        .forEach { key -> NbmsPill(prettyKey(key), MaterialTheme.nbms.statusPill(key)) }
                }
            }

            FormSection("Stat cards") {
                StatCard("Revenue (Month)", com.westly.nbms.core.util.Format.currency(20_735_000.0), NbmsIcons.Banknote, subtitle = "Net: ₦18,200,000")
                StatCard("Occupancy Rate", "72%", NbmsIcons.Bed, tone = BadgeTone.Gold, subtitle = "18/25 rooms")
                StatCard("Today's Activity", "9", NbmsIcons.Activity, tone = BadgeTone.Info, subtitle = "↑5 in · ↓4 out")
                StatCard("Pending Approvals", "3", NbmsIcons.Clock, tone = BadgeTone.Warning, subtitle = "Bookings awaiting review")
            }

            FormSection("Empty, error and loading") {
                NbmsCard {
                    EmptyState(
                        icon = NbmsIcons.CalendarCheck,
                        title = "No bookings found",
                        message = "Bookings will appear here once guests reserve a room.",
                        action = { NbmsButton("Add booking", onClick = {}, size = ButtonSize.Sm, leadingIcon = NbmsIcons.Plus) }
                    )
                }
                NbmsCard { ErrorState(message = "We couldn't load bookings.", onRetry = { toast.show("Retrying…", ToastType.Info) }) }
                NbmsCard { LoadingState() }
            }

            FormSection("Search and paged list") {
                SearchBar(search, { search = it }, placeholder = "Search by guest, room, booking ID…")
                if (filtered.isEmpty()) {
                    EmptyState(NbmsIcons.Search, "No results", "Try a different search.")
                } else {
                    PagedList(items = filtered, key = { it.id }, pageSize = 5) { item ->
                        NbmsCard(Modifier.fillMaxWidth(), onClick = { toast.show(item.title, ToastType.Info) }) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Avatar(name = "Guest ${item.id}", size = 32.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                                NbmsPill("pending", MaterialTheme.nbms.statusPill("pending"))
                            }
                        }
                    }
                }
            }

            FormSection("Dialogs and sheet") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NbmsButton("Open dialog", onClick = { showDialog = true }, variant = ButtonVariant.Outline)
                    NbmsButton("Confirm dialog", onClick = { showConfirm = true }, variant = ButtonVariant.Outline)
                    NbmsButton("Bottom sheet", onClick = { showSheet = true }, variant = ButtonVariant.Outline)
                }
            }

            FormSection("Pickers and avatars") {
                NbmsDatePickerField("Check-in date", date, { date = it })
                NbmsTimePickerField("Check-in time", time, { time = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("Westly Ibiese", size = 56.dp)
                    Avatar("Ada Obi")
                    Avatar("Tunde", size = 32.dp)
                    Avatar("")
                }
            }

            FormSection("Toasts") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NbmsButton("Success", onClick = { toast.show("Room 101 saved.", ToastType.Success, title = "Room Updated") }, variant = ButtonVariant.Gold)
                    NbmsButton("Error", onClick = { toast.show("Invalid email or password.", ToastType.Error, title = "Login failed") }, variant = ButtonVariant.Destructive)
                    NbmsButton("Info", onClick = { toast.show("Syncing your data.", ToastType.Info) })
                }
            }
        }

        if (showDialog) {
            NbmsDialog(
                title = "Create New User",
                description = "Dialog with a form inside.",
                onDismiss = { showDialog = false },
                confirmText = "Create User",
                onConfirm = {
                    showDialog = false
                    toast.show("Jane can now log in.", ToastType.Success, title = "User Created")
                }
            ) {
                NbmsTextField(name, { name = it }, label = "Full name", placeholder = "Jane Doe")
            }
        }
        if (showConfirm) {
            ConfirmDialog(
                title = "Delete Room 101?",
                message = "This action cannot be undone.",
                confirmText = "Delete",
                destructive = true,
                onConfirm = {
                    showConfirm = false
                    toast.show("Room 101 removed.", ToastType.Error, title = "Room Deleted")
                },
                onDismiss = { showConfirm = false }
            )
        }
        if (showSheet) {
            NbmsBottomSheet(onDismiss = { showSheet = false }, title = "Bottom sheet") {
                Text("Sheets slide up from the bottom and use the same colours.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                NbmsButton("Close", onClick = { showSheet = false }, modifier = Modifier.fillMaxWidth())
            }
        }

        NbmsSnackbarHost(controller = toast, modifier = Modifier.align(Alignment.TopCenter))
    }
}
