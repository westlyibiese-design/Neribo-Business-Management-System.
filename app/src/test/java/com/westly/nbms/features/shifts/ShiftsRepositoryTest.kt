package com.westly.nbms.features.shifts

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

private class FakeShiftsStore : ShiftsStore {
    val shifts = mutableListOf<Shift>()
    val observed = mutableListOf<Triple<String, String, String>>()
    val batches = mutableListOf<List<Map<String, Any?>>>()
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    val batchUpdates = mutableListOf<Pair<List<String>, Map<String, Any?>>>()
    var failWrites: Exception? = null

    override fun observe(roleKey: String, fromKey: String, toKey: String): Flow<Resource<List<Shift>>> {
        observed += Triple(roleKey, fromKey, toKey); return MutableStateFlow(Resource.Success(shifts.toList()))
    }
    override suspend fun byStaff(staffId: String) = shifts.filter { it.staffId == staffId }
    override suspend fun bySeries(seriesId: String) = shifts.filter { it.seriesId == seriesId }
    override suspend fun createBatch(docs: List<Map<String, Any?>>): List<String> {
        failWrites?.let { throw it }
        batches += docs; return docs.indices.map { "new$it" }
    }
    override suspend fun update(id: String, fields: Map<String, Any?>) { failWrites?.let { throw it }; updates += id to fields }
    override suspend fun updateBatch(ids: List<String>, fields: Map<String, Any?>) { failWrites?.let { throw it }; batchUpdates += ids to fields }
}

private class FakeShiftAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val id: String, val old: Map<String, Any?>?, val new: Map<String, Any?>?)
    val entries = mutableListOf<Entry>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

private class FakeShiftNotifier : Notifier {
    data class Call(val type: String, val message: String, val userIds: List<String>)
    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push down")
        calls += Call(type, message, forUserIds)
    }
}

class ShiftsRepositoryTest {
    private val store = FakeShiftsStore()
    private val audit = FakeShiftAudit()
    private val notifier = FakeShiftNotifier()
    private val repo = ShiftsRepository(store, audit, notifier)
    private val actor = ShiftActor("boss1", "Boss")
    private fun d(s: String) = LocalDate.parse(s)

    private fun input(
        date: String = "2026-10-12", start: String = "08:00", end: String = "16:00", next: Boolean = false,
        rec: ShiftRecurrence = ShiftRecurrence(), notes: String? = null, staff: String = "a"
    ) = ShiftInput(Role.RECEPTIONIST, staff, "Ada", d(date), start, end, next, "Front desk", notes, rec)

    private fun shift(id: String, date: String = "2026-10-12", start: String = "08:00", end: String = "16:00", staff: String = "a",
                      status: String = "scheduled", series: String? = null, label: String = "Front desk") =
        Shift(id = id, role = "receptionist", staffId = staff, staffName = "Ada", date = date, startTime = start, endTime = end,
            label = label, status = status, seriesId = series)

    // ── reads ──
    @Test fun observeAsksForTheRoleKeyAndBothDateKeys() = runTest {
        repo.observe(Role.WAITER, d("2026-10-11"), d("2026-10-17"))
        assertEquals(Triple("waiter", "2026-10-11", "2026-10-17"), store.observed.single())
    }
    @Test fun staffAndSeriesReadsDropCancelledShifts() = runTest {
        store.shifts += listOf(shift("1", series = "S"), shift("2", status = "cancelled", series = "S"), shift("3", staff = "b"))
        assertEquals(listOf("1"), repo.staffShifts("a").map { it.id })
        assertEquals(listOf("1"), repo.seriesShifts("S").map { it.id })
    }

    // ── create ──
    @Test fun conflictingCreateWritesNothing() = runTest {
        store.shifts += shift("old", start = "10:00", end = "12:00")
        val r = repo.create(input(), actor)
        assertTrue(r is ShiftWriteResult.Conflicts)
        assertEquals(listOf(ConflictInfo("2026-10-12", "Front desk", "10:00–12:00")), (r as ShiftWriteResult.Conflicts).conflicts)
        assertTrue(store.batches.isEmpty()); assertTrue(audit.entries.isEmpty()); assertTrue(notifier.calls.isEmpty())
    }

    @Test fun oneOffCreateWritesOneDocumentWithTheFullShape() = runTest {
        val r = repo.create(input(notes = "   "), actor) as ShiftWriteResult.Success
        assertEquals(1, r.count); assertEquals("new0", r.firstId)
        val doc = store.batches.single().single()
        assertEquals(
            setOf("role", "staffId", "staffName", "date", "startTime", "endTime", "endsNextDay", "label", "notes", "seriesId", "status",
                "createdBy", "createdByName", "createdAt"),
            doc.keys
        )
        assertEquals("receptionist", doc["role"]); assertEquals("a", doc["staffId"]); assertEquals("Ada", doc["staffName"])
        assertEquals("2026-10-12", doc["date"]); assertEquals("08:00", doc["startTime"]); assertEquals("16:00", doc["endTime"])
        assertEquals(false, doc["endsNextDay"]); assertEquals("Front desk", doc["label"])
        assertNull(doc["notes"]); assertNull(doc["seriesId"]); assertEquals("scheduled", doc["status"])
        assertEquals("boss1", doc["createdBy"]); assertEquals("Boss", doc["createdByName"]); assertSame(ShiftServerTime, doc["createdAt"])
    }

    @Test fun notesAreKeptWhenFilled() = runTest {
        repo.create(input(notes = "Bring keys"), actor)
        assertEquals("Bring keys", store.batches.single().single()["notes"])
    }

    @Test fun recurringCreateIsOneBatchWithASharedSeriesId() = runTest {
        val r = repo.create(input(rec = ShiftRecurrence(RecurrenceType.DAILY, until = d("2026-10-14"))), actor) as ShiftWriteResult.Success
        assertEquals(3, r.count)
        assertEquals(1, store.batches.size)
        val docs = store.batches.single()
        assertEquals(listOf("2026-10-12", "2026-10-13", "2026-10-14"), docs.map { it["date"] })
        val ids = docs.map { it["seriesId"] }.toSet()
        assertEquals(1, ids.size); assertNotNull(ids.single())
    }

    @Test fun createAuditsAndNotifiesForOneOff() = runTest {
        repo.create(input(), actor)
        val a = audit.entries.single()
        assertEquals("shift_created", a.action); assertEquals("shifts", a.collection); assertEquals("new0", a.id)
        assertEquals(mapOf<String, Any?>("staffName" to "Ada", "date" to "2026-10-12", "count" to 1), a.new)
        val n = notifier.calls.single()
        assertEquals("shift_assigned", n.type); assertEquals(listOf("a"), n.userIds)
        assertTrue(n.message, n.message.contains("on 2026-10-12, 08:00–16:00"))
    }

    @Test fun createNotifiesASeriesWithARangeSummary() = runTest {
        repo.create(input(rec = ShiftRecurrence(RecurrenceType.DAILY, until = d("2026-10-14"))), actor)
        assertTrue(notifier.calls.single().message, notifier.calls.single().message.contains("2026-10-12 → 2026-10-14 (3 shifts)"))
    }

    @Test fun createBlocksAConflictInsideARecurringSeries() = runTest {
        store.shifts += shift("old", date = "2026-10-13")
        val r = repo.create(input(rec = ShiftRecurrence(RecurrenceType.DAILY, until = d("2026-10-14"))), actor)
        assertEquals(listOf("2026-10-13"), (r as ShiftWriteResult.Conflicts).conflicts.map { it.date })
        assertTrue(store.batches.isEmpty())
    }

    @Test fun createWithNoMatchingDatesFailsWithAMessage() = runTest {
        try {
            repo.create(input(rec = ShiftRecurrence(RecurrenceType.DAILY, until = d("2026-10-01"))), actor)
            fail("expected failure")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun createFailureIsThrownAndSkipsAuditAndNotice() = runTest {
        store.failWrites = IllegalStateException("Permission denied")
        try { repo.create(input(), actor); fail("expected failure") } catch (e: IllegalStateException) { assertEquals("Permission denied", e.message) }
        assertTrue(audit.entries.isEmpty()); assertTrue(notifier.calls.isEmpty())
    }

    @Test fun auditAndNotifierFailuresNeverFailTheWrite() = runTest {
        audit.fail = true; notifier.fail = true
        val r = repo.create(input(), actor)
        assertTrue(r is ShiftWriteResult.Success); assertEquals(1, store.batches.size)
    }

    // ── update ──
    @Test fun updateIgnoresItsOwnShiftInTheConflictCheck() = runTest {
        val mine = shift("me")
        store.shifts += mine
        val r = repo.updateInstance(mine, ShiftUpdate("a", "Ada", "09:00", "17:00", false, "Front desk", null), actor)
        assertTrue(r is ShiftWriteResult.Success); assertEquals(1, (r as ShiftWriteResult.Success).count); assertEquals("me", r.firstId)
    }

    @Test fun updateConflictWithAnotherShiftWritesNothing() = runTest {
        val mine = shift("me", start = "08:00", end = "12:00")
        store.shifts += listOf(mine, shift("other", start = "13:00", end = "18:00", label = "Evening"))
        val r = repo.updateInstance(mine, ShiftUpdate("a", "Ada", "09:00", "14:00", false, "Front desk", null), actor)
        assertEquals(listOf(ConflictInfo("2026-10-12", "Evening", "13:00–18:00")), (r as ShiftWriteResult.Conflicts).conflicts)
        assertTrue(store.updates.isEmpty()); assertTrue(audit.entries.isEmpty()); assertTrue(notifier.calls.isEmpty())
    }

    @Test fun updateSendsOnlyTheChangedFieldsPlusUpdatedStamps() = runTest {
        val mine = shift("me")
        store.shifts += mine
        repo.updateInstance(mine, ShiftUpdate("a", "Ada", "09:00", "17:00", true, "Late desk", " "), actor)
        val (id, f) = store.updates.single()
        assertEquals("me", id)
        assertEquals(
            setOf("staffId", "staffName", "startTime", "endTime", "endsNextDay", "label", "notes", "updatedAt", "updatedBy", "updatedByName"), f.keys
        )
        assertEquals("09:00", f["startTime"]); assertEquals("17:00", f["endTime"]); assertEquals(true, f["endsNextDay"])
        assertEquals("Late desk", f["label"]); assertNull(f["notes"])
        assertSame(ShiftServerTime, f["updatedAt"]); assertEquals("boss1", f["updatedBy"]); assertEquals("Boss", f["updatedByName"])
    }

    @Test fun updateAuditsOldAndNewAndSendsUpdatedNoticeToSamePerson() = runTest {
        val mine = shift("me")
        store.shifts += mine
        repo.updateInstance(mine, ShiftUpdate("a", "Ada", "09:00", "17:00", false, "Front desk", null), actor)
        val a = audit.entries.single()
        assertEquals("shift_updated", a.action); assertEquals("me", a.id)
        assertEquals("08:00", a.old!!["startTime"]); assertEquals("09:00", a.new!!["startTime"])
        assertEquals("shift_updated", notifier.calls.single().type); assertEquals(listOf("a"), notifier.calls.single().userIds)
    }

    @Test fun reassigningChecksTheNewPersonAndNotifiesThemAsAssigned() = runTest {
        val mine = shift("me")
        store.shifts += listOf(mine, shift("b1", staff = "b", start = "10:00", end = "11:00"))
        val clash = repo.updateInstance(mine, ShiftUpdate("b", "Bola", "08:00", "16:00", false, "Front desk", null), actor)
        assertTrue(clash is ShiftWriteResult.Conflicts)
        store.shifts.removeAll { it.id == "b1" }
        repo.updateInstance(mine, ShiftUpdate("b", "Bola", "08:00", "16:00", false, "Front desk", null), actor)
        val n = notifier.calls.single()
        assertEquals("shift_assigned", n.type); assertEquals(listOf("b"), n.userIds)
        assertEquals("b", store.updates.single().second["staffId"])
    }

    // ── cancel ──
    @Test fun cancelInstanceWritesStatusAndStamps() = runTest {
        val s = shift("x")
        repo.cancelInstance(s, actor)
        val (id, f) = store.updates.single()
        assertEquals("x", id)
        assertEquals(setOf("status", "updatedAt", "updatedBy", "updatedByName"), f.keys)
        assertEquals("cancelled", f["status"]); assertSame(ShiftServerTime, f["updatedAt"]); assertEquals("boss1", f["updatedBy"])
        assertEquals("shift_cancelled", audit.entries.single().action); assertEquals("x", audit.entries.single().id)
        assertEquals("shift_cancelled", notifier.calls.single().type); assertEquals(listOf("a"), notifier.calls.single().userIds)
    }

    @Test fun cancelSeriesCancelsOnlyOnOrAfterTheDateInOneBatch() = runTest {
        val series = listOf(
            shift("1", date = "2026-10-10", series = "S"), shift("2", date = "2026-10-12", series = "S"),
            shift("3", date = "2026-10-13", series = "S", status = "cancelled"), shift("4", date = "2026-10-20", series = "S"),
            shift("5", date = "2026-10-21", series = "S", staff = "b")
        )
        val n = repo.cancelSeries(series, d("2026-10-12"), actor)
        assertEquals(3, n)
        val (ids, f) = store.batchUpdates.single()
        assertEquals(listOf("2", "4", "5"), ids)
        assertEquals("cancelled", f["status"]); assertEquals("boss1", f["updatedBy"])
        val a = audit.entries.single()
        assertEquals("shift_series_cancelled", a.action); assertEquals("S", a.id); assertEquals(3, a.new!!["count"])
        // one notice per affected person
        assertEquals(listOf(listOf("a"), listOf("b")), notifier.calls.map { it.userIds })
        assertTrue(notifier.calls.all { it.type == "shift_cancelled" })
    }

    @Test fun cancelSeriesWithNothingToCancelWritesNothing() = runTest {
        assertEquals(0, repo.cancelSeries(listOf(shift("1", date = "2026-10-01", series = "S")), d("2026-10-12"), actor))
        assertTrue(store.batchUpdates.isEmpty()); assertTrue(audit.entries.isEmpty()); assertTrue(notifier.calls.isEmpty())
    }

    @Test fun cancelFailureIsThrown() = runTest {
        store.failWrites = IllegalStateException("nope")
        try { repo.cancelInstance(shift("x"), actor); fail("expected failure") } catch (e: IllegalStateException) { assertEquals("nope", e.message) }
    }
}
