package com.westly.nbms.features.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaUploadStateTest {

    @Test fun picturesAreUploadedOneAfterAnotherInOrder() {
        val scope = TestScope(StandardTestDispatcher())
        val started = mutableListOf<String>()
        val gates = mapOf("a" to CompletableDeferred<Unit>(), "b" to CompletableDeferred<Unit>(), "c" to CompletableDeferred<Unit>())
        val state = MediaUploadState<String>(scope) { item ->
            started += item
            gates.getValue(item).await()
            "https://x/$item"
        }
        val urls = mutableListOf<String>()
        var summary: UploadSummary? = null

        assertTrue(state.start(listOf("a", "b", "c"), { urls += it }, { summary = it }))
        scope.runCurrent()
        assertEquals(listOf("a"), started)              // b and c have NOT started: no parallel uploads
        assertEquals(UploadProgress(true, 1, 3), state.progress.value)

        gates.getValue("a").complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("a", "b"), started)
        assertEquals(UploadProgress(true, 2, 3), state.progress.value)

        gates.getValue("b").complete(Unit)
        scope.runCurrent()
        gates.getValue("c").complete(Unit)
        scope.advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), started)
        assertEquals(listOf("https://x/a", "https://x/b", "https://x/c"), urls)
        assertEquals(UploadSummary(3, 0, null), summary)
        assertEquals(UploadProgress(), state.progress.value)
    }

    @Test fun aFailureInTheMiddleKeepsTheOthers() {
        val scope = TestScope(StandardTestDispatcher())
        val state = MediaUploadState<String>(scope) { item ->
            if (item == "bad") throw MediaException("That image is larger than 8MB. Please choose a smaller file.")
            "https://x/$item"
        }
        val urls = mutableListOf<String>()
        var summary: UploadSummary? = null

        state.start(listOf("one", "bad", "two"), { urls += it }, { summary = it })
        scope.advanceUntilIdle()

        assertEquals(listOf("https://x/one", "https://x/two"), urls)
        assertEquals(
            UploadSummary(2, 1, "That image is larger than 8MB. Please choose a smaller file."),
            summary
        )
    }

    @Test fun theFirstErrorMessageIsTheOneReported() {
        val scope = TestScope(StandardTestDispatcher())
        val state = MediaUploadState<String>(scope) { item -> throw MediaException("error for $item") }
        var summary: UploadSummary? = null

        state.start(listOf("x", "y"), { }, { summary = it })
        scope.advanceUntilIdle()

        assertEquals(UploadSummary(0, 2, "error for x"), summary)
    }

    @Test fun anErrorWithoutTextUsesTheGenericMessage() {
        val scope = TestScope(StandardTestDispatcher())
        val state = MediaUploadState<String>(scope) { throw IllegalStateException() }
        var summary: UploadSummary? = null

        state.start(listOf("x"), { }, { summary = it })
        scope.advanceUntilIdle()

        assertEquals(UploadSummary(0, 1, "Upload failed. Please try again."), summary)
    }

    @Test fun aSecondTapWhileUploadingIsIgnored() {
        val scope = TestScope(StandardTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val state = MediaUploadState<String>(scope) { item ->
            calls++
            gate.await()
            "https://x/$item"
        }
        val urls = mutableListOf<String>()

        assertTrue(state.start(listOf("first"), { urls += it }, { }))
        scope.runCurrent()
        assertFalse(state.start(listOf("second"), { urls += it }, { }))   // double tap
        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(listOf("https://x/first"), urls)
    }

    @Test fun anotherQueueCanStartAfterTheFirstEnded() {
        val scope = TestScope(StandardTestDispatcher())
        val state = MediaUploadState<String>(scope) { "https://x/$it" }
        val urls = mutableListOf<String>()

        assertTrue(state.start(listOf("a"), { urls += it }, { }))
        scope.advanceUntilIdle()
        assertTrue(state.start(listOf("b"), { urls += it }, { }))
        scope.advanceUntilIdle()

        assertEquals(listOf("https://x/a", "https://x/b"), urls)
    }

    @Test fun anEmptyPickStartsNothing() {
        val scope = TestScope(StandardTestDispatcher())
        val state = MediaUploadState<String>(scope) { "https://x/$it" }
        assertFalse(state.start(emptyList(), { }, { }))
        assertFalse(state.progress.value.active)
    }

    @Test fun afterDiscardTheQueueFinishesQuietly() {
        val scope = TestScope(StandardTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        var uploaded = 0
        val state = MediaUploadState<String>(scope) { item ->
            gate.await()
            uploaded++
            "https://x/$item"
        }
        val urls = mutableListOf<String>()
        var summary: UploadSummary? = null

        state.start(listOf("a", "b"), { urls += it }, { summary = it })
        scope.runCurrent()
        state.discard()                        // the screen was left mid-upload
        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertEquals(2, uploaded)              // the work still finished...
        assertTrue(urls.isEmpty())             // ...but nothing was reported
        assertNull(summary)
        assertFalse(state.progress.value.active)
    }

    @Test fun summaryIsReportedOnceAtTheEnd() {
        val scope = TestScope(StandardTestDispatcher())
        val state = MediaUploadState<String>(scope) { "https://x/$it" }
        var finished = 0
        var last: UploadSummary? = null

        state.start(listOf("a", "b"), { }, { finished++; last = it })
        scope.advanceUntilIdle()

        assertEquals(1, finished)
        assertNotNull(last)
        assertEquals(2, last!!.succeeded)
    }
}
