package com.westly.nbms.features.messages

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The realtime-to-refresh pipeline: bursts of events must cause one re-fetch (debounce 400 ms). */
class MessagesRefreshPipelineTest {

    @Test fun debounceWindowIs400Milliseconds() {
        assertEquals(400L, REFRESH_DEBOUNCE_MS)
    }

    @Test fun severalEventsWithinTheWindowCauseOneRefetch() = runTest {
        var fetches = 0
        flow {
            emit(Unit); delay(100)
            emit(Unit); delay(100)
            emit(Unit); delay(100)
            emit(Unit)
        }.collectDebounced { fetches++ }
        assertEquals(1, fetches)
    }

    @Test fun eventsFarApartEachCauseARefetch() = runTest {
        var fetches = 0
        flow {
            emit(Unit); delay(1_000)
            emit(Unit); delay(1_000)
            emit(Unit)
        }.collectDebounced { fetches++ }
        assertEquals(3, fetches)
    }

    @Test fun twoBurstsCauseTwoRefetches() = runTest {
        var fetches = 0
        flow {
            emit(Unit); delay(100)
            emit(Unit); delay(2_000)
            emit(Unit); delay(50)
            emit(Unit)
        }.collectDebounced { fetches++ }
        assertEquals(2, fetches)
    }

    @Test fun noEventsMeansNoRefetch() = runTest {
        var fetches = 0
        emptyFlow<Unit>().collectDebounced { fetches++ }
        assertEquals(0, fetches)
    }

    @Test fun theWindowCanBeChanged() = runTest {
        var fetches = 0
        flow {
            emit(Unit); delay(300)
            emit(Unit)
        }.collectDebounced(windowMs = 100) { fetches++ }
        assertEquals(2, fetches)
    }
}
