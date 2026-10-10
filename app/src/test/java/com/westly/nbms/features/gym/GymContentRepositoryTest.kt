package com.westly.nbms.features.gym

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory `cms_content/gym` with transaction behaviour: the writes apply only when the block finishes without a throw. */
internal class FakeGymContentStore : GymContentStore {
    val data = MutableStateFlow<Resource<Map<String, Any?>?>>(Resource.Success(null))
    val log = mutableListOf<String>()
    val writes = mutableListOf<Map<String, Any?>>()
    var transactions = 0
    var failWith: Exception? = null
    var delayMs = 0L

    override fun observeData(): Flow<Resource<Map<String, Any?>?>> = data

    override suspend fun <R> inTransaction(block: (GymContentTx) -> R): R {
        transactions++
        if (delayMs > 0) delay(delayMs)
        val pending = mutableListOf<Map<String, Any?>>()
        val tx = object : GymContentTx {
            override fun readDocument(): Map<String, Any?>? { log += "read"; return null }
            override fun mergeWrite(payload: Map<String, Any?>) { log += "write"; pending += payload }
        }
        val result = block(tx)
        failWith?.let { throw it }
        writes += pending
        return result
    }
}

class GymContentRepositoryTest {
    private val store = FakeGymContentStore()
    private val audit = FakeGymAudit()
    private val repo = GymContentRepository(store, audit)

    @Test fun aSaveIsOneTransactionThatReReadsThenWritesOnlyThatKeyAndUpdatedAt() = runTest {
        val value = listOf(mapOf("id" to "e1", "name" to "Bike"))
        repo.saveSection(GymSection.EQUIPMENT, value)

        assertEquals(1, store.transactions)
        assertEquals(listOf("read", "write"), store.log)
        assertEquals(1, store.writes.size)
        val payload = store.writes.single()
        assertEquals(setOf("data", "updatedAt"), payload.keys)
        assertSame(GymServerTime, payload["updatedAt"])
        val data = payload["data"] as Map<*, *>
        assertEquals(setOf("equipment"), data.keys)
        assertEquals(value, data["equipment"])
    }

    @Test fun everySectionWritesOnlyItsOwnKey() = runTest {
        GymSection.entries.forEach { repo.saveSection(it, "x") }
        assertEquals(
            GymSection.entries.map { it.key },
            store.writes.map { ((it["data"] as Map<*, *>).keys).single() }
        )
    }

    @Test fun thePayloadBuilderMatchesWhatIsWritten() {
        val payload = buildSectionMergePayload(GymSection.ABOUT, "Hello")
        assertEquals(mapOf("about" to "Hello"), payload["data"])
        assertEquals(2, payload.size)
    }

    @Test fun aSuccessfulSaveIsAudited() = runTest {
        repo.saveSection(GymSection.PACKAGES, emptyList<Any>())
        assertEquals(1, audit.entries.size)
        val e = audit.entries.single()
        assertEquals("gym_content_updated", e[0])
        assertEquals("cms_content", e[1])
        assertEquals("gym", e[2])
        assertEquals(mapOf("section" to "packages"), e[3])
    }

    @Test fun aFailedSaveThrowsAndIsNotAudited() = runTest {
        store.failWith = IllegalStateException("no access")
        try {
            repo.saveSection(GymSection.ABOUT, "x")
            fail("expected the failure")
        } catch (e: IllegalStateException) {
            assertEquals("no access", e.message)
        }
        assertTrue(store.writes.isEmpty())
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun aFailingAuditNeverTurnsASavedChangeIntoAnError() = runTest {
        audit.fail = true
        repo.saveSection(GymSection.GALLERY, listOf("u"))
        assertEquals(1, store.writes.size)
    }

    @Test fun aSaveThatNeverFinishesStopsWithAClearSentence() = runTest {
        store.delayMs = 60_000
        try {
            repo.saveSection(GymSection.ABOUT, "x")
            fail("expected the timeout")
        } catch (e: GymException) {
            assertEquals(MSG_CONTENT_SAVE_TIMEOUT, e.message)
        }
        assertTrue(store.writes.isEmpty())
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun aMissingDocumentIsEmptyContentNotAnError() = runTest {
        val first = repo.observe().first()
        assertEquals(Resource.Success(GymContent()), first)
    }

    @Test fun theDocumentIsReadTolerantlyAndErrorsPassThrough() = runTest {
        store.data.value = Resource.Success(mapOf("about" to "Hi", "equipment" to "junk", "gallery" to listOf("u")))
        val ok = repo.observe().first() as Resource.Success<GymContent>
        assertEquals("Hi", ok.data.about)
        assertTrue(ok.data.equipment.isEmpty())
        assertEquals(listOf("u"), ok.data.gallery)

        store.data.value = Resource.Error("denied")
        val err = repo.observe().first()
        assertTrue(err is Resource.Error)
        assertFalse(err is Resource.Success)

        store.data.value = Resource.Loading
        assertEquals(Resource.Loading, repo.observe().first())
    }
}
