package com.westly.nbms.features.housekeeping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCheckbox
import com.westly.nbms.core.design.NbmsDatePickerField
import com.westly.nbms.core.design.NbmsDialog
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.SearchBar
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomStatus
import com.westly.nbms.features.users.models.StaffUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

internal const val MSG_END_BEFORE_START = "End date must be on or after the start date."
internal const val MSG_ASSIGN_GENERIC = "Something went wrong. Please try again."

/** Everything the "Assign Rooms to Housekeeper" form holds. Dates are calendar days (no time zone). */
internal data class RoomAssignForm(
    val housekeeperId: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val ongoing: Boolean,
    val notes: String,
    val roomIds: Set<String>,
    val search: String
)

/** The form as it looks every time the dialog opens: today to today + 1 month, nothing typed, [preselected] rooms ticked. */
internal fun newRoomAssignForm(today: LocalDate, preselected: List<String>, defaultHousekeeperId: String?): RoomAssignForm =
    RoomAssignForm(
        housekeeperId = defaultHousekeeperId,
        startDate = today,
        endDate = today.plusMonths(1),
        ongoing = false,
        notes = "",
        roomIds = preselected.toSet(),
        search = ""
    )

internal enum class QuickRange { DAY, WEEK, MONTH }

/** "+1 Day" / "+1 Week" / "+1 Month": end date = start date + that (calendar maths, no time zone) and ongoing off. */
internal fun RoomAssignForm.withQuickEnd(range: QuickRange): RoomAssignForm = copy(
    endDate = when (range) {
        QuickRange.DAY -> startDate.plusDays(1)
        QuickRange.WEEK -> startDate.plusWeeks(1)
        QuickRange.MONTH -> startDate.plusMonths(1)
    },
    ongoing = false
)

/** The first thing wrong with the form: [title] always, [message] when there is more to say. */
internal enum class RoomAssignIssue(val title: String, val message: String?) {
    NO_HOUSEKEEPER("Select a housekeeper", null),
    NO_ROOMS("Select at least one room", null),
    INVALID_DATES("Invalid dates", MSG_END_BEFORE_START)
}

/** Checks in order: housekeeper, rooms, dates. null means the form may be sent. */
internal fun validateRoomAssign(form: RoomAssignForm): RoomAssignIssue? = when {
    form.housekeeperId.isNullOrEmpty() -> RoomAssignIssue.NO_HOUSEKEEPER
    form.roomIds.isEmpty() -> RoomAssignIssue.NO_ROOMS
    !form.ongoing && form.endDate.isBefore(form.startDate) -> RoomAssignIssue.INVALID_DATES
    else -> null
}

/** Room numbers sort by value ("2" before "10"); numbers come before names such as "Penthouse". */
internal fun sortRoomsForAssign(rooms: List<Room>): List<Room> =
    rooms.filter { !it.isDeleted }.sortedWith(
        Comparator { a, b ->
            val an = a.number.trim().toIntOrNull()
            val bn = b.number.trim().toIntOrNull()
            when {
                an != null && bn != null -> if (an != bn) an.compareTo(bn) else a.number.compareTo(b.number)
                an != null -> -1
                bn != null -> 1
                else -> a.number.compareTo(b.number, ignoreCase = true)
            }
        }
    )

/** Rooms whose number or type contains [query] (ignoring case). A blank query keeps everything. */
internal fun filterRoomsForAssign(rooms: List<Room>, query: String): List<Room> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return rooms
    return rooms.filter { it.number.lowercase().contains(q) || it.type.lowercase().contains(q) }
}

/** Active housekeeping staff, A to Z. */
internal fun housekeepersOf(users: List<StaffUser>): List<StaffUser> =
    users.filter { it.role == Role.HOUSEKEEPING.key && it.status == "active" }.sortedBy { it.name.lowercase() }

// ---- ViewModel ------------------------------------------------------------------------------

@HiltViewModel
class RoomAssignDialogViewModel @Inject constructor(
    firestore: BusinessFirestore,
    private val service: HousekeepingService,
    private val toast: ToastController,
    private val session: SessionManager
) : ViewModel() {

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Live rooms (deleted ones left out), by room number. */
    val rooms: StateFlow<Resource<List<Room>>> = firestore.observeList("rooms", Room::class.java)
        .map { r -> if (r is Resource.Success) Resource.Success(sortRoomsForAssign(r.data)) else r }
        .catch { emit(Resource.Error("We couldn't load rooms.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    /** Live active housekeeping staff, A to Z. */
    val housekeepers: StateFlow<Resource<List<StaffUser>>> = firestore.observeList("users", StaffUser::class.java)
        .map { r -> if (r is Resource.Success) Resource.Success(housekeepersOf(r.data)) else r }
        .catch { emit(Resource.Error("We couldn't load staff.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    /** Today's calendar day in the business time zone (the start-date default). */
    fun today(): LocalDate = LocalDate.now(zoneOfSession(session))

    /** Validates, saves, and tells the person. [onDone] runs only after a successful save. */
    internal fun assign(form: RoomAssignForm, rooms: List<Room>, housekeepers: List<StaffUser>, onDone: () -> Unit) {
        if (_saving.value) return
        val housekeeper = housekeepers.firstOrNull { it.id == form.housekeeperId }
        val knownRoomIds = rooms.map { it.id }.toSet()
        val clean = form.copy(housekeeperId = housekeeper?.id, roomIds = form.roomIds.filter { it in knownRoomIds }.toSet())
        val issue = validateRoomAssign(clean)
        if (issue != null || housekeeper == null) {
            val shown = issue ?: RoomAssignIssue.NO_HOUSEKEEPER
            if (shown.message == null) toast.show(shown.title, ToastType.Error) else toast.show(shown.message, ToastType.Error, shown.title)
            return
        }
        val signedIn = session.state.value as? SessionState.SignedIn
        if (signedIn == null) {
            toast.show(MSG_ASSIGN_GENERIC, ToastType.Error, "Failed to assign rooms")
            return
        }
        val actor = HousekeepingActor(signedIn.user.uid, signedIn.user.name)
        val refs = rooms.filter { it.id in clean.roomIds }.map { HousekeepingRoomRef(it.id, it.number, it.type) }

        _saving.value = true
        viewModelScope.launch {
            try {
                service.assignRooms(
                    housekeeperId = housekeeper.id,
                    housekeeperName = housekeeper.name,
                    rooms = refs,
                    startDate = clean.startDate,
                    endDate = if (clean.ongoing) null else clean.endDate,
                    notes = clean.notes.trim().ifEmpty { null },
                    actor = actor
                )
                toast.show("${refs.size} room(s) assigned to ${housekeeper.name}.", ToastType.Success, "Rooms Assigned")
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_ASSIGN_GENERIC, ToastType.Error, "Failed to assign rooms")
            } finally {
                _saving.value = false
            }
        }
    }
}

// ---- The dialog -----------------------------------------------------------------------------

/**
 * "Assign Rooms to Housekeeper": pick a housekeeper, a date range (or ongoing), optional instructions and the rooms.
 * Public and reusable: the Room Assignments page and the Overview (Part 23B) both open it.
 * Everything the person typed is thrown away each time it opens.
 */
@Composable
fun RoomAssignDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    preselectedRoomIds: List<String> = emptyList(),
    defaultHousekeeperId: String? = null,
    onDone: () -> Unit = {}
) {
    if (!open) return
    RoomAssignDialogContent(onDismiss, preselectedRoomIds, defaultHousekeeperId, onDone)
}

@Composable
private fun RoomAssignDialogContent(
    onDismiss: () -> Unit,
    preselectedRoomIds: List<String>,
    defaultHousekeeperId: String?,
    onDone: () -> Unit,
    vm: RoomAssignDialogViewModel = hiltViewModel()
) {
    val roomsState by vm.rooms.collectAsStateWithLifecycle()
    val housekeepersState by vm.housekeepers.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    // Leaving composition when the dialog closes is what resets this state for the next opening.
    var form by remember { mutableStateOf(newRoomAssignForm(vm.today(), preselectedRoomIds, defaultHousekeeperId)) }

    val rooms = (roomsState as? Resource.Success)?.data.orEmpty()
    val housekeepers = (housekeepersState as? Resource.Success)?.data.orEmpty()
    val selectedHousekeeper = housekeepers.firstOrNull { it.id == form.housekeeperId }

    NbmsDialog(
        title = "Assign Rooms to Housekeeper",
        onDismiss = { if (!saving) onDismiss() },
        confirmText = "Assign Rooms",
        onConfirm = {
            vm.assign(form, rooms, housekeepers) {
                onDismiss()
                onDone()
            }
        },
        loading = saving
    ) {
        // Housekeeper
        if (housekeepersState is Resource.Success && housekeepers.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Housekeeper", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "No active housekeeping staff found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            NbmsDropdown(
                label = "Housekeeper",
                options = housekeepers,
                selected = selectedHousekeeper,
                onSelect = { form = form.copy(housekeeperId = it.id) },
                optionLabel = { it.name },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Select housekeeper",
                enabled = !saving
            )
        }

        // Dates
        NbmsDatePickerField(
            label = "Start Date",
            value = form.startDate.toKotlinLocalDate(),
            onChange = { form = form.copy(startDate = it.toJavaLocalDate()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        )
        NbmsDatePickerField(
            label = "End Date",
            value = form.endDate.toKotlinLocalDate(),
            onChange = { form = form.copy(endDate = it.toJavaLocalDate()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving && !form.ongoing
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NbmsButton(text = "+1 Day", onClick = { form = form.withQuickEnd(QuickRange.DAY) }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = !saving)
            NbmsButton(text = "+1 Week", onClick = { form = form.withQuickEnd(QuickRange.WEEK) }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = !saving)
            NbmsButton(text = "+1 Month", onClick = { form = form.withQuickEnd(QuickRange.MONTH) }, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = !saving)
        }
        NbmsButton(
            text = if (form.ongoing) "Set end date" else "Make ongoing",
            onClick = { form = form.copy(ongoing = !form.ongoing) },
            variant = ButtonVariant.Link,
            size = ButtonSize.Sm,
            enabled = !saving
        )
        if (form.ongoing) {
            Text(
                "No end date — this assignment stays active until it's edited or ended.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Notes
        NbmsTextField(
            value = form.notes,
            onValueChange = { form = form.copy(notes = it) },
            label = "Special Instructions (optional)",
            modifier = Modifier.fillMaxWidth(),
            placeholder = "e.g. VIP wing, extra care with fixtures, guest has allergy notes on file...",
            singleLine = false,
            enabled = !saving
        )

        // Rooms
        Text(
            "Rooms (${form.roomIds.size} selected)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        SearchBar(
            value = form.search,
            onValueChange = { form = form.copy(search = it) },
            placeholder = "Search rooms"
        )
        val visible = filterRoomsForAssign(rooms, form.search)
        when {
            roomsState is Resource.Loading -> Text("Loading rooms…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            roomsState is Resource.Error -> Text("We couldn't load rooms.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            visible.isEmpty() -> Text("No rooms match.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(visible, key = { it.id }) { room ->
                    val checked = room.id in form.roomIds
                    val toggle = {
                        form = form.copy(roomIds = if (checked) form.roomIds - room.id else form.roomIds + room.id)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !saving, onClick = toggle)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NbmsCheckbox(checked = checked, onCheckedChange = { toggle() }, enabled = !saving)
                        Icon(NbmsIcons.Bed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Room ${room.number}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                            Text(room.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        NbmsPill(
                            text = RoomStatus.fromKey(room.status)?.label ?: room.status,
                            colors = MaterialTheme.nbms.statusPill(room.status)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
