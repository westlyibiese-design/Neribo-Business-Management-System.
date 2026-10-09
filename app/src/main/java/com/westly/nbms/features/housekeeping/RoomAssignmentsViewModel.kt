package com.westly.nbms.features.housekeeping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

internal const val MSG_ASSIGNMENTS_LOAD_FAILED = "We couldn't load room assignments."
internal const val MSG_ASSIGNMENTS_GENERIC = "Something went wrong. Please try again."

private val CALENDAR_DAY_TEXT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** The calendar day a stored date stands for: read in UTC, because calendar days are stored as UTC midnight. */
internal fun calendarDayOf(instant: Instant?): LocalDate? = instant?.atZone(ZoneOffset.UTC)?.toLocalDate()

/** "Mar 5, 2026" for a stored calendar day; "—" when there is none. */
internal fun calendarDayText(instant: Instant?): String =
    calendarDayOf(instant)?.let { CALENDAR_DAY_TEXT.format(it) } ?: "—"

/** "Mar 5, 2026 – Apr 5, 2026", or "Mar 5, 2026 · ongoing" when there is no end date. */
internal fun assignmentRangeText(start: Instant?, end: Instant?): String =
    if (end == null) "${calendarDayText(start)} · ongoing" else "${calendarDayText(start)} – ${calendarDayText(end)}"

/** Live groups for the page: deleted ones left out, newest first (a group still waiting for its server time counts as newest). */
internal fun sortGroupsNewestFirst(groups: List<RoomAssignmentGroup>): List<RoomAssignmentGroup> =
    groups.filter { !it.isDeleted }.sortedByDescending { it.createdAt ?: Instant.MAX }

/** The groups of one tab: "active" or "ended". */
internal fun groupsWithStatus(groups: List<RoomAssignmentGroup>, status: String): List<RoomAssignmentGroup> =
    groups.filter { it.status == status }

/** Up to 12 room numbers to show as chips, and how many more are hidden. */
internal data class RoomChips(val shown: List<String>, val more: Int)

internal fun roomChipsOf(roomNumbers: List<String>, limit: Int = 12): RoomChips =
    RoomChips(roomNumbers.take(limit), (roomNumbers.size - limit).coerceAtLeast(0))

/** True when the end date comes before the start date and the assignment is not ongoing. */
internal fun assignmentDatesInvalid(start: LocalDate, end: LocalDate, ongoing: Boolean): Boolean =
    !ongoing && end.isBefore(start)

// ---- ViewModel ------------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RoomAssignmentsViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val service: HousekeepingService,
    private val toast: ToastController,
    private val session: SessionManager
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    private val _saving = MutableStateFlow(false)
    /** True while an Edit is being saved. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _ending = MutableStateFlow(false)
    /** True while an End is running. */
    val ending: StateFlow<Boolean> = _ending.asStateFlow()

    /** Live `room_assignment_groups`, newest first. */
    val groups: StateFlow<Resource<List<RoomAssignmentGroup>>> = retryTick
        .flatMapLatest { observeGroups() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    fun retry() {
        retryTick.update { it + 1 }
    }

    private fun observeGroups(): Flow<Resource<List<RoomAssignmentGroup>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            firestore.collection("room_assignment_groups").addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(MSG_ASSIGNMENTS_LOAD_FAILED, error))
                } else if (snapshot != null) {
                    try {
                        // ESTIMATE shows a just-saved group's time at once.
                        val list = snapshot.documents.map { doc: DocumentSnapshot ->
                            parseAssignmentGroup(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                        }
                        trySend(Resource.Success(sortGroupsNewestFirst(list)))
                    } catch (e: Exception) {
                        trySend(Resource.Error(MSG_ASSIGNMENTS_LOAD_FAILED, e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(MSG_ASSIGNMENTS_LOAD_FAILED, e))
            null
        }
        awaitClose { registration?.remove() }
    }

    private fun actor(): HousekeepingActor? {
        val signedIn = session.state.value as? SessionState.SignedIn ?: return null
        return HousekeepingActor(signedIn.user.uid, signedIn.user.name)
    }

    /** Today's calendar day in the business time zone (the edit dialog's fallback date). */
    fun today(): LocalDate = LocalDate.now(zoneOfSession(session))

    /** Saves the Edit dialog. [onDone] runs only after a successful save so the dialog can close. */
    fun saveEdit(group: RoomAssignmentGroup, start: LocalDate, end: LocalDate, ongoing: Boolean, notes: String, onDone: () -> Unit) {
        if (_saving.value) return
        if (assignmentDatesInvalid(start, end, ongoing)) {
            toast.show(MSG_END_BEFORE_START, ToastType.Error, "Invalid dates")
            return
        }
        val actor = actor()
        if (actor == null) {
            toast.show(MSG_ASSIGNMENTS_GENERIC, ToastType.Error, "Failed to update")
            return
        }
        _saving.value = true
        viewModelScope.launch {
            try {
                service.updateAssignmentGroup(
                    group.id,
                    AssignmentGroupUpdate(startDate = start, changeEndDate = true, endDate = if (ongoing) null else end, notes = notes),
                    actor
                )
                toast.show("Assignment updated", ToastType.Success)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_ASSIGNMENTS_GENERIC, ToastType.Error, "Failed to update")
            } finally {
                _saving.value = false
            }
        }
    }

    /** Ends the group after the person confirmed. [onDone] runs only after success so the dialog can close. */
    fun endGroup(group: RoomAssignmentGroup, onDone: () -> Unit) {
        if (_ending.value) return
        val actor = actor()
        if (actor == null) {
            toast.show(MSG_ASSIGNMENTS_GENERIC, ToastType.Error, "Failed to end assignment")
            return
        }
        _ending.value = true
        viewModelScope.launch {
            try {
                service.endAssignmentGroup(group.id, actor)
                toast.show("${group.housekeeperName}'s rooms are now unassigned.", ToastType.Success, "Assignment ended")
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_ASSIGNMENTS_GENERIC, ToastType.Error, "Failed to end assignment")
            } finally {
                _ending.value = false
            }
        }
    }
}
