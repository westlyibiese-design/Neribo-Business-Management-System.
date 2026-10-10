package com.westly.nbms.features.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

internal const val MSG_MY_TASKS_LOAD_FAILED = "We couldn't load your tasks."
internal const val MSG_MY_TASKS_GENERIC = "Something went wrong. Please try again."
internal const val MSG_MY_TASKS_TIMEOUT = "That took too long. Check your connection and try again."
internal const val MY_TASKS_ACTION_TIMEOUT_MS = 20_000L
internal const val MY_TASKS_CLOCK_TICK_MS = 60_000L
internal const val SHIFTS_COLLECTION = "shifts"

/** Live shifts of one person. The real one reads `shifts`; the unit tests use a fake. */
internal interface MyShiftsSource {
    fun observe(uid: String): Flow<Resource<List<MyShiftDoc>>>
}

/** Reads `shifts where staffId == uid`. The date filter is applied by [MyTasksRules] so no extra database index is needed. */
internal class FirestoreMyShiftsSource(private val firestore: BusinessFirestore) : MyShiftsSource {
    override fun observe(uid: String): Flow<Resource<List<MyShiftDoc>>> =
        firestore.observeList(SHIFTS_COLLECTION, MyShiftDoc::class.java) { it.whereEqualTo("staffId", uid) }
}

/** One active card: the task and whether it is overdue right now. */
internal data class MyTaskRow(val task: StaffTask, val overdue: Boolean)

internal sealed interface MyTasksView {
    data object Loading : MyTasksView
    data class Error(val message: String) : MyTasksView

    /** [shifts] is empty (card hidden) until shifts exist; [finished] is empty (section hidden) when nothing is finished. */
    data class Ready(
        val active: List<MyTaskRow>,
        val finished: List<StaffTask>,
        val shifts: List<MyShiftRow>
    ) : MyTasksView
}

internal fun myTasksViewOf(
    tasks: Resource<List<StaffTask>>,
    shifts: Resource<List<MyShiftDoc>>,
    uid: String,
    now: Instant,
    zone: ZoneId
): MyTasksView = when (tasks) {
    is Resource.Loading -> MyTasksView.Loading
    is Resource.Error -> MyTasksView.Error(MSG_MY_TASKS_LOAD_FAILED)
    is Resource.Success -> MyTasksView.Ready(
        active = MyTasksRules.activeTasks(tasks.data).map { MyTaskRow(it, TaskRules.isOverdue(it, now)) },
        finished = MyTasksRules.recentlyFinished(tasks.data),
        // A shifts problem only hides the card; it never breaks the task list.
        shifts = (shifts as? Resource.Success)
            ?.let { MyTasksRules.shiftRows(it.data, uid, now.atZone(zone).toLocalDate()) }
            .orEmpty()
    )
}

/**
 * Behind My Tasks: my live tasks and shifts, and Accept / Start / Mark Complete.
 * [TasksRepository] only writes; this class audits, notifies and shows the toasts.
 */
@HiltViewModel
class MyTasksViewModel internal constructor(
    private val repository: TasksRepository,
    private val shiftsSource: MyShiftsSource,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val toast: ToastController,
    session: SessionManager,
    private val clock: () -> Instant
) : ViewModel() {

    @Inject
    constructor(
        repository: TasksRepository,
        firestore: BusinessFirestore,
        audit: AuditLogger,
        notifier: Notifier,
        toast: ToastController,
        session: SessionManager
    ) : this(repository, FirestoreMyShiftsSource(firestore), audit, notifier, toast, session, { Instant.now() })

    private val signedIn = session.state.value as? SessionState.SignedIn
    private val uid: String = signedIn?.user?.uid.orEmpty()
    private val myName: String = signedIn?.user?.name.orEmpty()
    private val zone: ZoneId = taskZoneOf(session)

    private val retryTick = MutableStateFlow(0)

    private val _busy = MutableStateFlow<Map<String, MyTaskAction>>(emptyMap())

    /** The task ids whose Accept / Start / Complete is running right now, with which action it is. */
    internal val busy: StateFlow<Map<String, MyTaskAction>> = _busy.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val myTasks: Flow<Resource<List<StaffTask>>> = retryTick
        .flatMapLatest {
            if (uid.isEmpty()) kotlinx.coroutines.flow.flowOf(Resource.Error(MSG_MY_TASKS_LOAD_FAILED))
            else repository.observeMine(uid)
        }
        .catch { emit(Resource.Error(MSG_MY_TASKS_LOAD_FAILED, it)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val myShifts: Flow<Resource<List<MyShiftDoc>>> = retryTick
        .flatMapLatest {
            if (uid.isEmpty()) kotlinx.coroutines.flow.flowOf(Resource.Success(emptyList()))
            else shiftsSource.observe(uid)
        }
        .catch { emit(Resource.Error(MSG_MY_TASKS_LOAD_FAILED, it)) }

    /** The current time, refreshed every minute so "Overdue" and "Today" stay right while the page is open. */
    private val now: Flow<Instant> = flow {
        while (true) {
            emit(clock())
            delay(MY_TASKS_CLOCK_TICK_MS)
        }
    }

    internal val view: StateFlow<MyTasksView> = combine(myTasks, myShifts, now) { tasks, shifts, instant ->
        myTasksViewOf(tasks, shifts, uid, instant, zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyTasksView.Loading)

    fun retry() = retryTick.update { it + 1 }

    fun accept(task: StaffTask) = run(task, MyTaskAction.ACCEPT)
    fun start(task: StaffTask) = run(task, MyTaskAction.START)
    fun complete(task: StaffTask) = run(task, MyTaskAction.COMPLETE)

    /**
     * Runs one action: the repository write first, then the audit entry, then (for Complete) the notification and toast.
     * A failed write shows an "Error" toast. The busy state is always cleared. A tap that no longer fits the task's
     * status, or on a task that is already busy, is ignored.
     */
    private fun run(task: StaffTask, action: MyTaskAction) {
        if (!MyTasksRules.isAllowed(task, action)) return
        if (task.id in _busy.value) return
        val oldStatus = task.status
        _busy.update { it + (task.id to action) }
        viewModelScope.launch {
            try {
                val newStatus = withTimeout(MY_TASKS_ACTION_TIMEOUT_MS) {
                    when (action) {
                        MyTaskAction.ACCEPT -> { repository.accept(task.id); TaskStatus.ACCEPTED }
                        MyTaskAction.START -> { repository.start(task.id); TaskStatus.IN_PROGRESS }
                        MyTaskAction.COMPLETE -> { repository.complete(task.id); TaskStatus.COMPLETED }
                    }
                }
                bestEffort {
                    audit.log(
                        auditAction(action), TASKS_COLLECTION, task.id,
                        mapOf("status" to oldStatus), mapOf("status" to newStatus.key)
                    )
                }
                if (action == MyTaskAction.COMPLETE) {
                    bestEffort { notifier.notifyTaskCompleted(myName, task.title, task.assignedBy.ifBlank { null }) }
                    toast.show(task.title, ToastType.Success, "Task Completed")
                }
            } catch (e: TimeoutCancellationException) {
                toast.show(MSG_MY_TASKS_TIMEOUT, ToastType.Error, "Error")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_MY_TASKS_GENERIC, ToastType.Error, "Error")
            } finally {
                _busy.update { it - task.id }
            }
        }
    }

    private fun auditAction(action: MyTaskAction): String = when (action) {
        MyTaskAction.ACCEPT -> "task_accepted"
        MyTaskAction.START -> "task_started"
        MyTaskAction.COMPLETE -> "task_completed"
    }

    /** Audit and notification are best-effort: a failure there never undoes an action that was saved. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MyTasks", "Best-effort step failed", e)
        }
    }
}
