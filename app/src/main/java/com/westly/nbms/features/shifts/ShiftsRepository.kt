package com.westly.nbms.features.shifts

import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal const val SHIFTS_COLLECTION = "shifts"

/** Marker for "server time"; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object ShiftServerTime

/** The database calls the shift code needs. [FirestoreShiftsStore] is the real one; unit tests use a fake. */
interface ShiftsStore {
    fun observe(roleKey: String, fromKey: String, toKey: String): Flow<Resource<List<Shift>>>
    suspend fun byStaff(staffId: String): List<Shift>
    suspend fun bySeries(seriesId: String): List<Shift>

    /** Creates one document per entry in ONE write batch; returns the new ids in the same order. */
    suspend fun createBatch(docs: List<Map<String, Any?>>): List<String>
    suspend fun update(id: String, fields: Map<String, Any?>)

    /** Applies the same [fields] to every id in ONE write batch. */
    suspend fun updateBatch(ids: List<String>, fields: Map<String, Any?>)
}

class FirestoreShiftsStore(private val firestore: BusinessFirestore) : ShiftsStore {
    override fun observe(roleKey: String, fromKey: String, toKey: String): Flow<Resource<List<Shift>>> =
        firestore.observeList(SHIFTS_COLLECTION, Shift::class.java) {
            it.whereEqualTo("role", roleKey)
                .whereGreaterThanOrEqualTo("date", fromKey)
                .whereLessThanOrEqualTo("date", toKey)
        }

    override suspend fun byStaff(staffId: String): List<Shift> =
        firestore.collection(SHIFTS_COLLECTION).whereEqualTo("staffId", staffId).get().await().toObjects(Shift::class.java)

    override suspend fun bySeries(seriesId: String): List<Shift> =
        firestore.collection(SHIFTS_COLLECTION).whereEqualTo("seriesId", seriesId).get().await().toObjects(Shift::class.java)

    override suspend fun createBatch(docs: List<Map<String, Any?>>): List<String> {
        val col = firestore.collection(SHIFTS_COLLECTION)
        val batch = col.firestore.batch()
        val ids = docs.map { fields ->
            val ref = col.document()
            batch.set(ref, resolveShiftValues(fields))
            ref.id
        }
        batch.commit().await()
        return ids
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        firestore.update(SHIFTS_COLLECTION, id, resolveShiftValues(fields))
    }

    override suspend fun updateBatch(ids: List<String>, fields: Map<String, Any?>) {
        val col = firestore.collection(SHIFTS_COLLECTION)
        val batch = col.firestore.batch()
        val resolved = resolveShiftValues(fields)
        ids.forEach { batch.update(col.document(it), resolved) }
        batch.commit().await()
    }
}

private fun resolveShiftValues(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) ->
    if (v is ShiftServerTime) FieldValue.serverTimestamp() else v
}

/**
 * Reads and writes `shifts`, with the best-effort audit and notification calls. It shows no toasts and holds no
 * "saving" state (the screen does). Conflicts come back as [ShiftWriteResult.Conflicts]; every other failure is thrown.
 */
@Singleton
class ShiftsRepository(
    private val store: ShiftsStore,
    private val audit: AuditLogger,
    private val notifier: Notifier
) {
    @Inject
    constructor(firestore: BusinessFirestore, audit: AuditLogger, notifier: Notifier) :
        this(FirestoreShiftsStore(firestore), audit, notifier)

    /** Live shifts of [role] starting from [fromDate] to [toDate] inclusive. Cancelled shifts are included. */
    fun observe(role: Role, fromDate: LocalDate, toDate: LocalDate): Flow<Resource<List<Shift>>> =
        store.observe(role.key, fromDate.toDateKey(), toDate.toDateKey())

    /** All the person's non-cancelled shifts. */
    suspend fun staffShifts(staffId: String): List<Shift> =
        store.byStaff(staffId).filter { it.status != ShiftStatus.CANCELLED.key }

    /** All non-cancelled shifts of a series. */
    suspend fun seriesShifts(seriesId: String): List<Shift> =
        store.bySeries(seriesId).filter { it.status != ShiftStatus.CANCELLED.key }

    suspend fun create(input: ShiftInput, actor: ShiftActor): ShiftWriteResult {
        val dates = ShiftLogic.generateOccurrenceDates(input.startDate, input.recurrence)
        if (dates.isEmpty()) throw IllegalStateException("No dates match the repeat settings. Check the days and the end date.")
        val existing = staffShifts(input.staffId)
        val conflicts = ShiftLogic.findConflicts(dates, input.startTime, input.endTime, input.endsNextDay, existing)
        if (conflicts.isNotEmpty()) return ShiftWriteResult.Conflicts(conflicts)

        val seriesId = if (input.recurrence.type == RecurrenceType.NONE) null else UUID.randomUUID().toString()
        val docs = dates.map { date ->
            mapOf<String, Any?>(
                "role" to input.role.key,
                "staffId" to input.staffId,
                "staffName" to input.staffName,
                "date" to date.toDateKey(),
                "startTime" to input.startTime,
                "endTime" to input.endTime,
                "endsNextDay" to input.endsNextDay,
                "label" to input.label,
                "notes" to input.notes?.takeIf { it.isNotBlank() },
                "seriesId" to seriesId,
                "status" to ShiftStatus.SCHEDULED.key,
                "createdBy" to actor.uid,
                "createdByName" to actor.name,
                "createdAt" to ShiftServerTime
            )
        }
        val ids = store.createBatch(docs)
        val first = dates.first().toDateKey()
        val summary = if (dates.size == 1) first else "$first → ${dates.last().toDateKey()} (${dates.size} shifts)"
        bestEffort {
            audit.log("shift_created", SHIFTS_COLLECTION, ids.firstOrNull().orEmpty(), null,
                mapOf("staffName" to input.staffName, "date" to first, "count" to dates.size))
        }
        bestEffort {
            notifier.notifyShiftAssigned(actor.name, input.label, summary, input.startTime, input.endTime, listOf(input.staffId))
        }
        return ShiftWriteResult.Success(dates.size, ids.firstOrNull())
    }

    suspend fun updateInstance(shift: Shift, updates: ShiftUpdate, actor: ShiftActor): ShiftWriteResult {
        val date = shift.date.toLocalDateOrNull() ?: throw IllegalStateException("This shift has an invalid date.")
        val others = staffShifts(updates.staffId)
        val conflicts = ShiftLogic.findConflicts(listOf(date), updates.startTime, updates.endTime, updates.endsNextDay, others, excludeShiftId = shift.id)
        if (conflicts.isNotEmpty()) return ShiftWriteResult.Conflicts(conflicts)

        val notes = updates.notes?.takeIf { it.isNotBlank() }
        store.update(
            shift.id,
            mapOf(
                "staffId" to updates.staffId,
                "staffName" to updates.staffName,
                "startTime" to updates.startTime,
                "endTime" to updates.endTime,
                "endsNextDay" to updates.endsNextDay,
                "label" to updates.label,
                "notes" to notes,
                "updatedAt" to ShiftServerTime,
                "updatedBy" to actor.uid,
                "updatedByName" to actor.name
            )
        )
        bestEffort {
            audit.log(
                "shift_updated", SHIFTS_COLLECTION, shift.id,
                mapOf("staffId" to shift.staffId, "staffName" to shift.staffName, "startTime" to shift.startTime, "endTime" to shift.endTime,
                    "endsNextDay" to shift.endsNextDay, "label" to shift.label, "notes" to shift.notes, "date" to shift.date),
                mapOf("staffId" to updates.staffId, "staffName" to updates.staffName, "startTime" to updates.startTime, "endTime" to updates.endTime,
                    "endsNextDay" to updates.endsNextDay, "label" to updates.label, "notes" to notes, "date" to shift.date)
            )
        }
        bestEffort {
            if (updates.staffId != shift.staffId) {
                notifier.notifyShiftAssigned(actor.name, updates.label, shift.date, updates.startTime, updates.endTime, listOf(updates.staffId))
            } else {
                notifier.notifyShiftUpdated(actor.name, updates.label, shift.date, listOf(updates.staffId))
            }
        }
        return ShiftWriteResult.Success(1, shift.id)
    }

    suspend fun cancelInstance(shift: Shift, actor: ShiftActor) {
        store.update(shift.id, cancelFields(actor))
        bestEffort {
            audit.log("shift_cancelled", SHIFTS_COLLECTION, shift.id,
                mapOf("status" to shift.status), mapOf("status" to ShiftStatus.CANCELLED.key, "staffName" to shift.staffName, "date" to shift.date))
        }
        bestEffort { notifier.notifyShiftCancelled(actor.name, shift.label, shift.date, listOf(shift.staffId)) }
    }

    /** Cancels every non-cancelled shift of the series dated [fromDate] or later. Returns how many were cancelled. */
    suspend fun cancelSeries(seriesShifts: List<Shift>, fromDate: LocalDate, actor: ShiftActor): Int {
        val targets = ShiftLogic.selectSeriesToCancel(seriesShifts, fromDate)
        if (targets.isEmpty()) return 0
        store.updateBatch(targets.map { it.id }, cancelFields(actor))
        bestEffort {
            audit.log("shift_series_cancelled", SHIFTS_COLLECTION, targets.first().seriesId ?: targets.first().id,
                null, mapOf("count" to targets.size, "fromDate" to fromDate.toDateKey()))
        }
        targets.groupBy { it.staffId }.forEach { (staffId, list) ->
            bestEffort { notifier.notifyShiftCancelled(actor.name, list.first().label, fromDate.toDateKey(), listOf(staffId)) }
        }
        return targets.size
    }

    private fun cancelFields(actor: ShiftActor): Map<String, Any?> = mapOf(
        "status" to ShiftStatus.CANCELLED.key,
        "updatedAt" to ShiftServerTime,
        "updatedBy" to actor.uid,
        "updatedByName" to actor.name
    )

    /** Audit and notification problems never fail the write. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // swallowed on purpose
        }
    }
}
