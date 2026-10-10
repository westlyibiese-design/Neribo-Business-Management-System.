package com.westly.nbms.features.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

// ---- Public inputs of the dialog (section 2.5 of the phase prompt) ----------------------------

/** Values other screens can pre-fill when they open the dialog for a new task. */
data class TaskDefaults(
    val type: TaskType? = null,
    val title: String? = null,
    val description: String? = null,
    val relatedCollection: String? = null,
    val relatedId: String? = null,
    val relatedLabel: String? = null
)

/** The task being handed to somebody else (reassign mode). */
data class ReassignTarget(val id: String, val title: String)

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

internal const val TASK_SAVE_TIMEOUT_MS = 20_000L
internal const val MSG_TASK_GENERIC = "Something went wrong. Please try again."
internal const val MSG_TASK_TIMEOUT = "Saving took too long. Check your connection and try again."
internal const val MSG_TASK_NOT_SIGNED_IN = "Not signed in"

/** Everything the dialog holds while it is open. */
internal data class TaskAssignForm(
    val type: TaskType = TaskType.OTHER,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val title: String = "",
    val description: String = "",
    val dueTime: LocalTime? = null,
    val selectedIds: Set<String> = emptySet(),
    val suggestedOnly: Boolean = true,
    val search: String = ""
)

/** The form as it looks every time the dialog opens. */
internal fun newTaskAssignForm(defaults: TaskDefaults?): TaskAssignForm = TaskAssignForm(
    type = defaults?.type ?: TaskType.OTHER,
    title = defaults?.title.orEmpty(),
    description = defaults?.description.orEmpty()
)

/** Changing the type throws away the chosen staff (they were picked for the old type). */
internal fun TaskAssignForm.withType(newType: TaskType): TaskAssignForm =
    if (newType == type) this else copy(type = newType, selectedIds = emptySet())

/** The first thing wrong with the form: a destructive toast with [title] and [message]. */
internal enum class TaskAssignIssue(val title: String, val message: String) {
    MISSING_TITLE("Missing title", "Give the task a short title."),
    NO_STAFF("No staff selected", "Select at least one staff member.")
}

/** Title first (new tasks only), then staff. null means the form may be sent. */
internal fun validateTaskAssign(form: TaskAssignForm, reassign: Boolean): TaskAssignIssue? = when {
    !reassign && form.title.isBlank() -> TaskAssignIssue.MISSING_TITLE
    form.selectedIds.isEmpty() -> TaskAssignIssue.NO_STAFF
    else -> null
}

internal fun taskZoneOf(session: SessionManager): ZoneId {
    val name = (session.state.value as? SessionState.SignedIn)?.business?.timezone
    return try {
        ZoneId.of(name ?: "Africa/Lagos")
    } catch (e: Exception) {
        ZoneId.of("Africa/Lagos")
    }
}

/** Builds the new task from the form. `createdAt` stays null (the server fills it); status starts "pending". */
internal fun buildNewTask(
    form: TaskAssignForm,
    defaults: TaskDefaults?,
    ids: List<String>,
    names: List<String>,
    byUid: String,
    byName: String,
    dueAt: Instant?
): StaffTask = StaffTask(
    title = form.title.trim(),
    type = form.type.key,
    description = form.description.trim().ifEmpty { null },
    priority = form.priority.key,
    assignedToIds = ids,
    assignedToNames = names,
    assignedBy = byUid,
    assignedByName = byName,
    dueAt = dueAt?.let { Timestamp(it.epochSecond, it.nano) },
    status = TaskStatus.PENDING.key,
    relatedCollection = defaults?.relatedCollection,
    relatedId = defaults?.relatedId,
    relatedLabel = defaults?.relatedLabel,
    isDeleted = false
)

// ---- Staff source (the `users` mirror) ---------------------------------------------------------

/** Live active staff. The real one reads the `users` mirror; the unit tests use a fake. */
interface TaskStaffSource {
    fun observe(): Flow<Resource<List<TaskStaffUser>>>
}

/** Keeps only active, not-deleted accounts and A-to-Z by name. */
internal fun activeTaskStaff(docs: List<TaskUserDoc>): List<TaskStaffUser> =
    docs.filter { it.status == "active" && !it.isDeleted }
        .map { TaskStaffUser(id = it.id, name = it.name, role = it.role) }
        .sortedBy { it.name.lowercase() }

class FirestoreTaskStaffSource(private val firestore: BusinessFirestore) : TaskStaffSource {
    override fun observe(): Flow<Resource<List<TaskStaffUser>>> =
        firestore.observeList("users", TaskUserDoc::class.java).map { r ->
            when (r) {
                is Resource.Success -> Resource.Success(activeTaskStaff(r.data))
                is Resource.Error -> Resource.Error(r.message, r.cause)
                is Resource.Loading -> Resource.Loading
            }
        }
}

// ---- ViewModel ------------------------------------------------------------------------------

/**
 * Behind [TaskAssignDialog]: the staff list, validation, saving, the audit entry, the notification and the toasts.
 * The audit entry and the notification are best-effort; a failure there never undoes a saved task.
 */
@HiltViewModel
class TaskAssignViewModel internal constructor(
    staffSource: TaskStaffSource,
    private val repository: TasksRepository,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val toast: ToastController,
    private val session: SessionManager
) : ViewModel() {

    @Inject
    constructor(
        firestore: BusinessFirestore,
        repository: TasksRepository,
        audit: AuditLogger,
        notifier: Notifier,
        toast: ToastController,
        session: SessionManager
    ) : this(FirestoreTaskStaffSource(firestore), repository, audit, notifier, toast, session)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Live active staff, A to Z (nobody is filtered by role here; [TaskRules.assignablePool] does that). */
    val staff: StateFlow<Resource<List<TaskStaffUser>>> = staffSource.observe()
        .catch { emit(Resource.Error("We couldn't load staff.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    /** Today's date in the business time zone. */
    fun today(): LocalDate = LocalDate.now(taskZoneOf(session))

    /**
     * Checks the form, saves, then audits, notifies and shows the toast. [onDone] runs only after a successful save.
     * [allStaff] is the live staff list; the names that are saved come from it, in A-to-Z order.
     */
    internal fun submit(
        form: TaskAssignForm,
        defaults: TaskDefaults?,
        reassignTask: ReassignTarget?,
        allStaff: List<TaskStaffUser>,
        onDone: () -> Unit
    ) {
        if (_saving.value) return
        val reassign = reassignTask != null
        val issue = validateTaskAssign(form, reassign)
        if (issue != null) {
            toast.show(issue.message, ToastType.Error, issue.title)
            return
        }
        // Only people who are still in the live staff list can be assigned.
        val chosen = TaskRules.assignablePool(allStaff, form.type, suggestedOnly = false, search = "")
            .filter { it.id in form.selectedIds }
        if (chosen.isEmpty()) {
            toast.show(TaskAssignIssue.NO_STAFF.message, ToastType.Error, TaskAssignIssue.NO_STAFF.title)
            return
        }
        val me = (session.state.value as? SessionState.SignedIn)?.user
        if (me == null) {
            toast.show(MSG_TASK_NOT_SIGNED_IN, ToastType.Error, "Error")
            return
        }
        val ids = chosen.map { it.id }
        val names = chosen.map { it.name }
        val namesText = names.joinToString(", ")

        _saving.value = true
        viewModelScope.launch {
            try {
                if (reassignTask != null) {
                    withTimeout(TASK_SAVE_TIMEOUT_MS) { repository.reassign(reassignTask.id, ids, names) }
                    bestEffort { audit.log("task_reassigned", TASKS_COLLECTION, reassignTask.id, null, mapOf("assignedToNames" to names)) }
                    bestEffort { notifier.notifyTaskReassigned(me.name, reassignTask.title, ids) }
                    toast.show("Now assigned to $namesText", ToastType.Success, "Task Reassigned")
                } else {
                    val dueAt = TaskRules.dueAtFromTime(form.dueTime, today(), taskZoneOf(session))
                    val task = buildNewTask(form, defaults, ids, names, me.uid, me.name, dueAt)
                    val newId = withTimeout(TASK_SAVE_TIMEOUT_MS) { repository.create(task) }
                    bestEffort { audit.log("task_assigned", TASKS_COLLECTION, newId, null, mapOf("title" to task.title, "assignedToNames" to names)) }
                    bestEffort { notifier.notifyTaskAssigned(me.name, task.title, task.priority, ids) }
                    toast.show("${task.title} → $namesText", ToastType.Success, "Task Assigned")
                }
                onDone()
            } catch (e: TimeoutCancellationException) {
                toast.show(MSG_TASK_TIMEOUT, ToastType.Error, "Error")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_TASK_GENERIC, ToastType.Error, "Error")
            } finally {
                _saving.value = false
            }
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("TaskAssign", "Best-effort step failed", e)
        }
    }
}
