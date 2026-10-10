package com.westly.nbms.features.cms

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A fake repository for the Website CMS tests: keeps every document, applies list operations with the real rules (and the
 * caller's `normalize`) and re-emits the changed document, like the live listener does.
 */
internal class FakeWebsiteCmsSource : CmsSource {
    private val docs = mutableMapOf<String, MutableStateFlow<Resource<CmsRawDoc>>>()

    /** `docId` and the object passed to every [saveObject]. */
    val savedObjects = mutableListOf<Pair<String, Map<String, Any?>>>()
    val ops = mutableListOf<ListOp<*>>()
    val limitsSeen = mutableListOf<Int>()
    val auditActions = mutableListOf<String>()
    var failWith: Exception? = null
    var forced: MutateResult? = null
    var gate: CompletableDeferred<Unit>? = null

    private fun flowFor(docId: String) =
        docs.getOrPut(docId) { MutableStateFlow<Resource<CmsRawDoc>>(Resource.Success(CmsRawDoc(exists = false, data = null))) }

    /** Makes [docId] exist with [data] (a Map for objects, a List of maps for lists). */
    fun put(docId: String, data: Any?) {
        flowFor(docId).value = Resource.Success(CmsRawDoc(true, data))
    }

    fun failToLoad(docId: String) {
        flowFor(docId).value = Resource.Error("offline")
    }

    fun stayLoading(docId: String) {
        flowFor(docId).value = Resource.Loading
    }

    fun dataOf(docId: String): Any? = (flowFor(docId).value as Resource.Success).data.data

    fun testimonials(): List<TestimonialItem> = TestimonialItem.parseList(dataOf(DOC_TESTIMONIALS))
    fun faqs(): List<FaqItem> = FaqItem.parseList(dataOf(DOC_FAQS))

    override fun observeDoc(docId: String): Flow<Resource<CmsRawDoc>> = flowFor(docId)

    override suspend fun saveObject(docId: String, data: Map<String, Any?>) {
        gate?.await()
        failWith?.let { throw it }
        savedObjects += docId to data
        auditActions += "cms_updated:$docId"
        flowFor(docId).value = Resource.Success(CmsRawDoc(true, data))
    }

    override suspend fun <T> mutateList(
        docId: String,
        op: ListOp<T>,
        codec: ListCodec<T>,
        limit: Int,
        auditAction: String,
        normalize: (List<T>) -> List<T>
    ): MutateResult {
        gate?.await()
        failWith?.let { throw it }
        ops += op
        limitsSeen += limit
        forced?.let { return it }
        val current = codec.parse(dataOf(docId))
        return when (val outcome = applyListOp(current, op, codec.idOf, limit)) {
            is ListOpOutcome.Changed -> {
                put(docId, normalize(outcome.list).map(codec.toMap))
                auditActions += auditAction
                MutateResult.Done
            }
            ListOpOutcome.Unchanged -> MutateResult.Done
            ListOpOutcome.AlreadyChanged -> MutateResult.AlreadyChanged
            ListOpOutcome.LimitReached -> MutateResult.LimitReached
        }
    }
}
