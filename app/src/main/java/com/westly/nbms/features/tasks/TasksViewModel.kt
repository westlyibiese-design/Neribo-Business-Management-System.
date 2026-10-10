package com.westly.nbms.features.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

internal const val MSG_TASKS_LOAD_FAILED = "We couldn't load tasks."
internal const val MSG_TASKS_GENERIC = "Something went wrong. Please try again."
internal const val MSG_TASKS_TIMEOUT = "Cancelling took too long. Check your connection and try again."
internal const val TASKS_CANCEL_TIMEOUT_MS = 20_000L

/** How often the page re-checks "overdue" while it stays open. */
internal const val TASKS_CLOCK_TICK_MS = 60_000L

/** What the three filters on the page are set to. Defaults: nothing typed, "Active", "All Types". */
internal data class TasksFilters(
    val search: String = "",
    val status: TaskStatusFilter = TaskStatusFilter.ACTIVE,
    /** null = "All Types". */
    val type: TaskType? = null
)

/** One card on the page: the task and whether it is overdue right now. */
internal data class TaskRow(val task: StaffTask, val overdue: Boolean)

internal sealed interface TasksView {
    data object Loading : TasksView
    data class Error(val message: String) : TasksView

    /** [stats] count every task of the business; [rows] are only the ones the filters let through. */
    data class Ready(val stats: TaskStats, val rows: List<TaskRow>) : TasksView
}

/** Only the Super Admin and the Operations Manager may create tasks; the Manager can still reassign and cancel. */
internal fun canAssignTasks(role: Role?): Boolean = role == Role.SUPER_ADMIN || role == Role.OPERATIONS_MANAGER

/** Reassign and Cancel are offered on every task that is not completed or cancelled. */
internal fun canManageTask(task: StaffTask): Boolean = TaskRules.isActive(task)

/** Label of a status filter, as shown in the dropdown. */
internal fun taskStatusFilterLabel(filter: TaskStatusFilter): String = when (filter) {
    TaskStatusFilter.ACTIVE -> "Active"
    TaskStatusFilter.ALL -> "All Status"
    TaskStatusFilter.PENDING -> TaskStatus.PENDING.label
    TaskStatusFilter.ACCEPTED -> TaskStatus.ACCEPTED.label
    TaskStatusFilter.IN_PROGRESS -> TaskStatus.IN_PROGRESS.label
    TaskStatusFilter.COMPLETED -> TaskStatus.COMPLETED.label
    TaskStatusFilter.CANCELLED -> TaskStatus.CANCELLED.label
}

/** Label of the type filter: "All Types" or the type's own label. */
internal fun taskTypeFilterLabel(type: TaskType?): String = type?.label ?: "All Types"

/**
 * The page's content: stats over every (not deleted) task, then the filtered list with overdue tasks first and the
 * newest first after that. All of the rules come from [TaskRules].
 */
internal fun tasksViewOf(
    resource: Resource<List<StaffTask>>,
    filters: TasksFilters,
    now: Instant,
    zone: ZoneId
): TasksView = when (resource) {
    is Resource.Loading -> TasksView.Loading
    is Resource.Error -> TasksView.Error(MSG_TASKS_LOAD_FAILED)
    is Resource.Success -> {
        val visible = TaskRules.visibleTasks(resource.data)
        val shown = TaskRules.orderForAssignment(
            TaskRules.filter(visible, filters.search, filters.status, filters.type),
            now
        )
        TasksView.Ready(
            stats = TaskRules.stats(visible, now, zone),
            rows = shown.map { TaskRow(it, TaskRules.isOverdue(it, now)) }
        )
    }
}

/**
 * Behind the Task Assignment page: the live task list, the three filters, the stats, and Cancel.
 * Creating and reassigning are done by [TaskAssignDialog]; this class only cancels, then audits and shows the toast.
 */
@HiltViewModel
class TasksViewModel internal constructor(
    private val repository: TasksRepository,
    private val audit: AuditLogger,
    private val toast: ToastController,
    session: SessionManager,
    private val clock: () -> Instant
) : ViewModel() {

    @Inject
    constructor(
        repository: TasksRepository,
        audit: AuditLogger,
        toast: ToastController,
        session: SessionManager
    ) : this(repository, audit, toast, session, { Instant.now() })

    private val signedIn = session.state.value as? SessionState.SignedIn
    private val zone: ZoneId = taskZoneOf(session)

    /** True only for the Super Admin and the Operations Manager: they see the **Assign Task** button. */
    val canAssign: Boolean = canAssignTasks(signedIn?.user?.role)

    private val _filters = MutableStateFlow(TasksFilters())
    internal val filters: StateFlow<TasksFilters> = _filters.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    /** Ids of the tasks whose Cancel is running right now (each card shows a spinner for its own task). */
    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<Resource<List<StaffTask>>> = retryTick
        .flatMapLatest { repository.observeAll() }
        .catch { emit(Resource.Error(MSG_TASKS_LOAD_FAILED, it)) }

    /** The current time, refreshed every minute so a task turns "Overdue" without reopening the page. */
    private val now: Flow<Instant> = flow {
        while (true) {
            emit(clock())
            delay(TASKS_CLOCK_TICK_MS)
        }
    }

    internal val view: StateFlow<TasksView> = combine(live, _filters, now) { resource, filters, instant ->
        tasksViewOf(resource, filters, instant, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksView.Loading)

    fun setSearch(search: String) = _filters.update { it.copy(search = search) }
    fun setStatus(status: TaskStatusFilter) = _filters.update { it.copy(status = status) }
    fun setType(type: TaskType?) = _filters.update { it.copy(type = type) }
    fun retry() = retryTick.update { it + 1 }

    /**
     * Cancels [task]: the repository write first, then the `task_cancelled` audit entry (`{status: old}` to
     * `{status: "cancelled"}`) and the "Task Cancelled" toast. A failure shows an "Error" toast. The busy state
     * is always cleared. A task that is already completed or cancelled, or already being cancelled, is ignored.
     */
    fun cancel(task: StaffTask) {
        if (!canManageTask(task)) return
        if (task.id in _busyIds.value) return
        val oldStatus = task.status
        _busyIds.update { it + task.id }
        viewModelScope.launch {
            try {
                withTimeout(TASKS_CANCEL_TIMEOUT_MS) { repository.cancel(task.id) }
                bestEffort {
                    audit.log(
                        "task_cancelled", TASKS_COLLECTION, task.id,
                        mapOf("status" to oldStatus), mapOf("status" to TaskStatus.CANCELLED.key)
                    )
                }
                toast.show(task.title, ToastType.Success, "Task Cancelled")
            } catch (e: TimeoutCancellationException) {
                toast.show(MSG_TASKS_TIMEOUT, ToastType.Error, "Error")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_TASKS_GENERIC, ToastType.Error, "Error")
            } finally {
                _busyIds.update { it - task.id }
            }
        }
    }

    /** The audit entry is best-effort: a failure there never undoes a cancel that was saved. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Tasks", "Best-effort step failed", e)
        }
    }
}
