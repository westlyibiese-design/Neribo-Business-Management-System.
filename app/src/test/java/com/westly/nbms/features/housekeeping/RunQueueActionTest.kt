package com.westly.nbms.features.housekeeping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunQueueActionTest {

    @Test
    fun successMessageWithoutRebalancing() {
        assertEquals(
            "1 checkout + 0 occupied-service task(s) created.",
            runQueueSuccessMessage(RunQueueCounts(checkout = 1, occupied = 0, rebalanced = 0))
        )
    }

    @Test
    fun successMessageMentionsRebalancedTasks() {
        assertEquals(
            "3 checkout + 2 occupied-service task(s) created (2 rebalanced for fairness).",
            runQueueSuccessMessage(RunQueueCounts(checkout = 3, occupied = 2, rebalanced = 2))
        )
    }

    @Test
    fun parsesCountsFromServerReply() {
        val reply = """{"ok":true,"ranAt":"2026-03-05T09:00:00.000Z","checkoutTasksCreated":2,"occupiedServiceTasksCreated":4,
            "skippedAlreadyExisted":1,"unassignedCount":0,"rebalancedCount":1,"overdueCheckoutsNotified":0,"cleaningRemindersSent":0,"errors":[]}"""
        val counts = parseRunQueueReply(reply).getOrThrow()
        assertEquals(RunQueueCounts(checkout = 2, occupied = 4, rebalanced = 1), counts)
    }

    @Test
    fun missingCountsCountAsZero() {
        val counts = parseRunQueueReply("""{"ok":true}""").getOrThrow()
        assertEquals(RunQueueCounts(0, 0, 0), counts)
    }

    @Test
    fun serverErrorMessageIsShown() {
        val result = parseRunQueueReply("""{"ok":false,"error":"Unauthorized"}""")
        assertTrue(result.isFailure)
        assertEquals("Unauthorized", result.exceptionOrNull()?.message)
    }

    @Test
    fun unreadableReplyUsesTheGenericFailure() {
        val result = parseRunQueueReply("not json at all")
        assertTrue(result.isFailure)
        assertEquals("Failed to run queue generator.", result.exceptionOrNull()?.message)
    }

    @Test
    fun serverErrorExtractionHandlesEscapesAndBlanks() {
        assertEquals("Say \"hi\"", runQueueServerError("""{"ok":false,"error":"Say \"hi\""}"""))
        assertNull(runQueueServerError(null, "", "no error here"))
    }
}
