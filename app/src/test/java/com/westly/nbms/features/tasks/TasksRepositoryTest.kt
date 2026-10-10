package com.westly.nbms.features.tasks

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.westly.nbms.core.data.Resource

class TasksRepositoryTest {
    private val store = FakeTasksStore()
    private fun repo(session: FakeTasksSession = FakeTasksSession(uid = "u9", name = "Ada Obi")) = TasksRepository(store, session)

    @Test fun createWritesTheFullNewTaskDocument() = runTest {
        val task = StaffTask(
            title = "Clean 204", type = "housekeeping", description = "Deep clean", priority = "high",
            assignedToIds = listOf("a", "b"), assignedToNames = listOf("Ada", "Bola"), assignedBy = "me", assignedByName = "Boss",
            dueAt = ts(TASK_NOW), status = "pending", relatedCollection = "bookings", relatedId = "bk1", relatedLabel = "Room 204"
        )
        val id = repo().create(task)
        assertEquals("task1", id)
        val f = store.created.single()
        assertEquals(
            setOf(
                "title", "type", "description", "priority", "assignedToIds", "assignedToNames", "assignedBy", "assignedByName",
                "dueAt", "status", "relatedCollection", "relatedId", "relatedLabel", "acceptedBy", "acceptedByName", "acceptedAt",
                "completedAt", "createdAt", "isDeleted"
            ),
            f.keys
        )
        assertEquals("Clean 204", f["title"]); assertEquals("housekeeping", f["type"]); assertEquals("Deep clean", f["description"])
        assertEquals("high", f["priority"]); assertEquals(listOf("a", "b"), f["assignedToIds"]); assertEquals(listOf("Ada", "Bola"), f["assignedToNames"])
        assertEquals("me", f["assignedBy"]); assertEquals("Boss", f["assignedByName"]); assertEquals(ts(TASK_NOW), f["dueAt"])
        assertEquals("pending", f["status"])
        assertEquals("bookings", f["relatedCollection"]); assertEquals("bk1", f["relatedId"]); assertEquals("Room 204", f["relatedLabel"])
        listOf("acceptedBy", "acceptedByName", "acceptedAt", "completedAt").forEach { assertNull(it, f[it]) }
        assertSame(TaskServerTime, f["createdAt"])
        assertEquals(false, f["isDeleted"])
    }

    @Test fun createKeepsNullOptionalFieldsNull() = runTest {
        repo().create(StaffTask(title = "t", assignedToIds = listOf("a"), assignedToNames = listOf("A")))
        val f = store.created.single()
        assertNull(f["description"]); assertNull(f["dueAt"]); assertNull(f["relatedCollection"]); assertNull(f["relatedId"]); assertNull(f["relatedLabel"])
    }

    @Test fun reassignPayload() = runTest {
        repo().reassign("t1", listOf("x", "y"), listOf("Xavier", "Yetunde"))
        val (id, f) = store.updates.single()
        assertEquals("t1", id)
        assertEquals(
            setOf("assignedToIds", "assignedToNames", "status", "acceptedBy", "acceptedByName", "acceptedAt", "reassignedAt", "reassignedBy", "reassignedByName", "updatedAt"),
            f.keys
        )
        assertEquals(listOf("x", "y"), f["assignedToIds"]); assertEquals(listOf("Xavier", "Yetunde"), f["assignedToNames"])
        assertEquals("pending", f["status"])
        listOf("acceptedBy", "acceptedByName", "acceptedAt").forEach { assertNull(it, f[it]) }
        assertSame(TaskServerTime, f["reassignedAt"]); assertSame(TaskServerTime, f["updatedAt"])
        assertEquals("u9", f["reassignedBy"]); assertEquals("Ada Obi", f["reassignedByName"])
    }

    @Test fun cancelPayload() = runTest {
        repo().cancel("t1")
        val (id, f) = store.updates.single()
        assertEquals("t1", id)
        assertEquals(setOf("status", "updatedAt"), f.keys)
        assertEquals("cancelled", f["status"]); assertSame(TaskServerTime, f["updatedAt"])
    }

    @Test fun acceptPayloadUsesTheSignedInUser() = runTest {
        repo().accept("t1")
        val f = store.updates.single().second
        assertEquals(setOf("status", "acceptedBy", "acceptedByName", "acceptedAt", "updatedAt"), f.keys)
        assertEquals("accepted", f["status"]); assertEquals("u9", f["acceptedBy"]); assertEquals("Ada Obi", f["acceptedByName"])
        assertSame(TaskServerTime, f["acceptedAt"]); assertSame(TaskServerTime, f["updatedAt"])
    }

    @Test fun startPayload() = runTest {
        repo().start("t1")
        val f = store.updates.single().second
        assertEquals(setOf("status", "updatedAt"), f.keys)
        assertEquals("in_progress", f["status"])
    }

    @Test fun completePayload() = runTest {
        repo().complete("t1")
        val f = store.updates.single().second
        assertEquals(setOf("status", "completedAt", "updatedAt"), f.keys)
        assertEquals("completed", f["status"]); assertSame(TaskServerTime, f["completedAt"]); assertSame(TaskServerTime, f["updatedAt"])
    }

    @Test fun everyWriteSetsUpdatedAt() = runTest {
        val r = repo()
        r.reassign("t", listOf("a"), listOf("A")); r.cancel("t"); r.accept("t"); r.start("t"); r.complete("t")
        assertEquals(5, store.updates.size)
        store.updates.forEach { assertSame(TaskServerTime, it.second["updatedAt"]) }
    }

    @Test fun signedOutWritesThatNeedTheUserFailAndWriteNothing() = runTest {
        val r = repo(FakeTasksSession(signedIn = false))
        for (call in listOf<suspend () -> Unit>({ r.accept("t") }, { r.reassign("t", listOf("a"), listOf("A")) })) {
            try { call(); fail("expected failure") } catch (e: IllegalStateException) { assertEquals("Not signed in", e.message) }
        }
        assertTrue(store.updates.isEmpty())
    }

    @Test fun storeFailuresReachTheCaller() = runTest {
        store.failWith = IllegalStateException("rules said no")
        try { repo().complete("t"); fail("expected failure") } catch (e: IllegalStateException) { assertEquals("rules said no", e.message) }
    }

    @Test fun observeAllPassesTheListThrough() = runTest {
        val list = listOf(taskOf("a"), taskOf("b", deleted = true))
        store.all.value = Resource.Success(list)
        val got = repo().observeAll().first() as Resource.Success
        assertEquals(list, got.data)
    }

    @Test fun observeMineAsksForThatUser() = runTest {
        repo().observeMine("u42").first()
        assertEquals(listOf("u42"), store.mineQueries)
    }

    @Test fun createFieldsHelperNeverLeaksTheDocumentId() {
        assertFalse("id" in StaffTask(id = "zzz", title = "t").toCreateFields().keys)
    }
}
