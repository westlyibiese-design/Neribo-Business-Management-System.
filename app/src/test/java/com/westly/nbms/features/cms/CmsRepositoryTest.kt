package com.westly.nbms.features.cms

import com.westly.nbms.core.audit.AuditLogger
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

internal class FakeCmsAudit : AuditLogger {
    val entries = mutableListOf<Triple<String, String, String>>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Triple(action, collection, documentId)
    }
}

/**
 * An in-memory `cms_content/{docId}` with transaction behaviour: a transaction re-reads the CURRENT document, and its writes
 * apply only when the block finishes without a throw.
 */
internal class FakeCmsStore(var doc: CmsRawDoc = CmsRawDoc(false, null)) : CmsStore {
    /** Fields of the document other than data/updatedAt; a save must never touch them. */
    val otherFields = mutableMapOf<String, Any?>("createdBy" to "someone")
    val flow = MutableStateFlow<Resource<CmsRawDoc>>(Resource.Success(doc))
    val log = mutableListOf<String>()
    val writes = mutableListOf<Map<String, Any?>>()
    val mergeFieldsUsed = mutableListOf<List<String>>()
    var transactions = 0
    var failWith: Exception? = null
    var delayMs = 0L

    /** Runs just before a transaction reads, to play "someone else saved in the meantime". */
    var beforeRead: (() -> Unit)? = null

    override fun observe(docId: String): Flow<Resource<CmsRawDoc>> = flow

    override suspend fun setMerged(docId: String, payload: Map<String, Any?>, mergeFields: List<String>) {
        if (delayMs > 0) delay(delayMs)
        failWith?.let { throw it }
        mergeFieldsUsed += mergeFields
        writes += payload
        // mergeFields: only the named fields are written, everything else stays.
        payload.filterKeys { it in mergeFields }["data"]?.let { doc = CmsRawDoc(true, it) }
        flow.value = Resource.Success(doc)
    }

    override suspend fun <R> inTransaction(docId: String, block: (CmsTx) -> R): R {
        transactions++
        if (delayMs > 0) delay(delayMs)
        beforeRead?.invoke()
        val pending = mutableListOf<Map<String, Any?>>()
        val tx = object : CmsTx {
            override fun read(): CmsRawDoc { log += "read"; return doc }
            override fun write(payload: Map<String, Any?>) { log += "write"; pending += payload }
        }
        val result = block(tx)
        failWith?.let { throw it }
        pending.forEach { payload ->
            writes += payload
            mergeFieldsUsed += CMS_MERGE_FIELDS
            doc = CmsRawDoc(true, payload["data"])
        }
        flow.value = Resource.Success(doc)
        return result
    }
}

class CmsRepositoryTest {
    private val store = FakeCmsStore()
    private val audit = FakeCmsAudit()
    private val repo = CmsRepository(store, audit)

    private val codec = ListCodec(
        parse = { FacilityItem.parseList(it) },
        toMap = { it: FacilityItem -> it.toMap() },
        idOf = { it: FacilityItem -> it.id }
    )

    private fun fac(id: String, name: String = "Name $id") = FacilityItem(id, name, "", "Desc $id")

    private fun seed(vararg items: FacilityItem) {
        store.doc = CmsRawDoc(true, items.map { it.toMap() })
        store.flow.value = Resource.Success(store.doc)
    }

    private fun current(): List<FacilityItem> = FacilityItem.parseList(store.doc.data)

    private suspend fun mutate(op: ListOp<FacilityItem>, limit: Int = 20, normalize: (List<FacilityItem>) -> List<FacilityItem> = { it }) =
        repo.mutateList("facilities", op, codec, limit, "facilities_updated", normalize)

    // ── observing ──

    @Test fun aMissingDocumentIsASuccessWithNoDataNotAnError() = runTest {
        val first = repo.observeDoc("facilities").first()
        assertEquals(Resource.Success(CmsRawDoc(exists = false, data = null)), first)
    }

    @Test fun aPresentDocumentIsPassedThroughWithItsRawData() = runTest {
        seed(fac("a"))
        val first = repo.observeDoc("facilities").first() as Resource.Success
        assertTrue(first.data.exists)
        assertEquals(listOf("a"), FacilityItem.parseList(first.data.data).map { it.id })
    }

    @Test fun anErrorStaysAnError() = runTest {
        store.flow.value = Resource.Error("denied")
        assertTrue(repo.observeDoc("facilities").first() is Resource.Error)
    }

    // ── list operations ──

    @Test fun addAppendsAndWritesDataAndUpdatedAtOnly() = runTest {
        seed(fac("a"))
        val result = mutate(ListOp.Add(fac("b")))
        assertSame(MutateResult.Done, result)
        assertEquals(listOf("a", "b"), current().map { it.id })
        val payload = store.writes.single()
        assertEquals(setOf("data", "updatedAt"), payload.keys)
        assertSame(CmsServerTime, payload["updatedAt"])
        assertEquals(listOf(CMS_MERGE_FIELDS), store.mergeFieldsUsed)
    }

    @Test fun aListSaveIsOneTransactionThatReadsFirstThenWrites() = runTest {
        seed(fac("a"))
        mutate(ListOp.Add(fac("b")))
        assertEquals(1, store.transactions)
        assertEquals(listOf("read", "write"), store.log)
    }

    @Test fun addToAMissingDocumentCreatesTheList() = runTest {
        val result = mutate(ListOp.Add(fac("a")))
        assertSame(MutateResult.Done, result)
        assertEquals(listOf("a"), current().map { it.id })
    }

    @Test fun replaceKeepsThePosition() = runTest {
        seed(fac("a"), fac("b"), fac("c"))
        assertSame(MutateResult.Done, mutate(ListOp.Replace(fac("b", "Renamed"))))
        assertEquals(listOf("a", "b", "c"), current().map { it.id })
        assertEquals("Renamed", current()[1].name)
    }

    @Test fun removeTakesOutOnlyThatItem() = runTest {
        seed(fac("a"), fac("b"), fac("c"))
        assertSame(MutateResult.Done, mutate(ListOp.Remove("b")))
        assertEquals(listOf("a", "c"), current().map { it.id })
    }

    @Test fun moveSwapsWithTheNeighbour() = runTest {
        seed(fac("a"), fac("b"), fac("c"))
        assertSame(MutateResult.Done, mutate(ListOp.Move("b", -1)))
        assertEquals(listOf("b", "a", "c"), current().map { it.id })
        assertSame(MutateResult.Done, mutate(ListOp.Move("b", 1)))
        assertEquals(listOf("a", "b", "c"), current().map { it.id })
        assertSame(MutateResult.Done, mutate(ListOp.Move("a", 1)))
        assertEquals(listOf("b", "a", "c"), current().map { it.id })
    }

    @Test fun movingTheFirstUpOrTheLastDownWritesAndAuditsNothing() = runTest {
        seed(fac("a"), fac("b"))
        assertSame(MutateResult.Done, mutate(ListOp.Move("a", -1)))
        assertSame(MutateResult.Done, mutate(ListOp.Move("b", 1)))
        assertTrue(store.writes.isEmpty())
        assertTrue(audit.entries.isEmpty())
        assertEquals(listOf("a", "b"), current().map { it.id })
    }

    @Test fun addingBeyondTheLimitIsRefusedAndNothingIsWritten() = runTest {
        seed(fac("a"), fac("b"))
        assertSame(MutateResult.LimitReached, mutate(ListOp.Add(fac("c")), limit = 2))
        assertTrue(store.writes.isEmpty())
        assertTrue(audit.entries.isEmpty())
        assertEquals(2, current().size)
    }

    @Test fun anAddJustUnderTheLimitIsAccepted() = runTest {
        seed(fac("a"))
        assertSame(MutateResult.Done, mutate(ListOp.Add(fac("b")), limit = 2))
        assertEquals(2, current().size)
    }

    @Test fun theLimitDoesNotBlockReplaceRemoveOrMoveOnAFullList() = runTest {
        seed(fac("a"), fac("b"))
        assertSame(MutateResult.Done, mutate(ListOp.Replace(fac("a", "New")), limit = 2))
        assertSame(MutateResult.Done, mutate(ListOp.Move("a", 1), limit = 2))
        assertSame(MutateResult.Done, mutate(ListOp.Remove("b"), limit = 2))
    }

    @Test fun replaceRemoveAndMoveOfAVanishedIdAreAlreadyChangedAndWriteNothing() = runTest {
        seed(fac("a"))
        assertSame(MutateResult.AlreadyChanged, mutate(ListOp.Replace(fac("gone"))))
        assertSame(MutateResult.AlreadyChanged, mutate(ListOp.Remove("gone")))
        assertSame(MutateResult.AlreadyChanged, mutate(ListOp.Move("gone", 1)))
        assertTrue(store.writes.isEmpty())
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun addingAnIdThatIsAlreadyThereIsAlreadyChanged() = runTest {
        seed(fac("a"))
        assertSame(MutateResult.AlreadyChanged, mutate(ListOp.Add(fac("a", "Again"))))
        assertEquals(1, current().size)
    }

    @Test fun twoPeopleEditingDifferentItemsBothSurvive() = runTest {
        seed(fac("a"), fac("b"))
        // Both people loaded [a, b]. The first renames a; the second (still looking at the old list) renames b.
        assertSame(MutateResult.Done, mutate(ListOp.Replace(fac("a", "A by Ada"))))
        assertSame(MutateResult.Done, mutate(ListOp.Replace(fac("b", "B by Bola"))))
        assertEquals(listOf("A by Ada", "B by Bola"), current().map { it.name })
    }

    @Test fun aChangeMadeByAnotherPersonJustBeforeTheReadIsKept() = runTest {
        seed(fac("a"))
        // Someone else adds "x" after this person opened the screen but before this save reads the document.
        store.beforeRead = { store.doc = CmsRawDoc(true, listOf(fac("a"), fac("x")).map { it.toMap() }) }
        assertSame(MutateResult.Done, mutate(ListOp.Add(fac("b"))))
        assertEquals(listOf("a", "x", "b"), current().map { it.id })
    }

    @Test fun aRemoveOfAnItemSomeoneElseAlreadyRemovedIsAlreadyChanged() = runTest {
        seed(fac("a"), fac("b"))
        store.beforeRead = { store.doc = CmsRawDoc(true, listOf(fac("a")).map { it.toMap() }) }
        assertSame(MutateResult.AlreadyChanged, mutate(ListOp.Remove("b")))
        assertEquals(listOf("a"), current().map { it.id })
    }

    @Test fun normalizeRunsOnTheResultingListBeforeItIsWritten() = runTest {
        val faqCodec = ListCodec(
            parse = { FaqItem.parseList(it) },
            toMap = { it: FaqItem -> it.toMap() },
            idOf = { it: FaqItem -> it.id }
        )
        store.doc = CmsRawDoc(true, listOf(FaqItem("f1", "Q1", "A1", 7), FaqItem("f2", "Q2", "A2", 7)).map { it.toMap() })
        repo.mutateList("faqs", ListOp.Add(FaqItem("f3", "Q3", "A3", 0)), faqCodec, CmsLimits.FAQS, "cms_updated:faqs", CmsListRules::renumberFaqs)
        assertEquals(listOf(1, 2, 3), FaqItem.parseList(store.doc.data).map { it.order })
        repo.mutateList("faqs", ListOp.Move("f3", -2), faqCodec, CmsLimits.FAQS, "cms_updated:faqs", CmsListRules::renumberFaqs)
        val after = FaqItem.parseList(store.doc.data)
        assertEquals(listOf("f3", "f1", "f2"), after.map { it.id })
        assertEquals(listOf(1, 2, 3), after.map { it.order })
    }

    @Test fun anUnreadableStoredListIsTreatedAsEmptyForAnAdd() = runTest {
        store.doc = CmsRawDoc(true, "not a list")
        assertSame(MutateResult.Done, mutate(ListOp.Add(fac("a"))))
        assertEquals(listOf("a"), current().map { it.id })
    }

    // ── audit ──

    @Test fun aSuccessfulListChangeIsAuditedWithTheCallersActionOnCmsContent() = runTest {
        seed(fac("a"))
        mutate(ListOp.Add(fac("b")))
        assertEquals(listOf(Triple("facilities_updated", "cms_content", "facilities")), audit.entries)
    }

    @Test fun aFailedAuditNeverTurnsASavedChangeIntoAnError() = runTest {
        seed(fac("a"))
        audit.fail = true
        assertSame(MutateResult.Done, mutate(ListOp.Add(fac("b"))))
        assertEquals(2, current().size)
    }

    // ── failures ──

    @Test fun aFailedTransactionThrowsAndWritesNothing() = runTest {
        seed(fac("a"))
        store.failWith = IllegalStateException("denied")
        try {
            mutate(ListOp.Add(fac("b")))
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("denied", e.message)
        }
        assertEquals(listOf("a"), current().map { it.id })
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun aTransactionThatTakesLongerThanTwentySecondsFailsWithAFriendlyMessage() = runTest {
        seed(fac("a"))
        store.delayMs = 25_000
        try {
            mutate(ListOp.Add(fac("b")))
            fail("should have thrown")
        } catch (e: CmsException) {
            assertEquals(MSG_CMS_SAVE_TIMEOUT, e.message)
        }
        assertTrue(audit.entries.isEmpty())
    }

    // ── object saves ──

    @Test fun anObjectSaveReplacesTheWholeDataFieldAndTouchesOnlyDataAndUpdatedAt() = runTest {
        store.doc = CmsRawDoc(true, mapOf("headline" to "Old", "oldKey" to "to be removed"))
        val newData = HeroContent(headline = "New", ctaText = "Book").toMap()
        repo.saveObject("hero", newData)

        val payload = store.writes.single()
        assertEquals(setOf("data", "updatedAt"), payload.keys)
        assertEquals(newData, payload["data"])
        assertSame(CmsServerTime, payload["updatedAt"])
        assertEquals(listOf(listOf("data", "updatedAt")), store.mergeFieldsUsed)
        // the removed key really disappeared; the unrelated top-level field is still there
        assertFalse((store.doc.data as Map<*, *>).containsKey("oldKey"))
        assertEquals("someone", store.otherFields["createdBy"])
    }

    @Test fun anObjectSaveIsAuditedAsCmsUpdatedWithTheDocId() = runTest {
        repo.saveObject("about", AboutContent(title = "Us").toMap())
        assertEquals(listOf(Triple("cms_updated:about", "cms_content", "about")), audit.entries)
    }

    @Test fun aFailedObjectSaveThrowsAndIsNotAudited() = runTest {
        store.failWith = IllegalStateException("denied")
        try {
            repo.saveObject("hero", HeroContent().toMap())
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("denied", e.message)
        }
        assertTrue(audit.entries.isEmpty())
    }

    @Test fun anObjectSaveThatHangsFailsAfterTwentySeconds() = runTest {
        store.delayMs = 25_000
        try {
            repo.saveObject("hero", HeroContent().toMap())
            fail("should have thrown")
        } catch (e: CmsException) {
            assertEquals(MSG_CMS_SAVE_TIMEOUT, e.message)
        }
    }
}
