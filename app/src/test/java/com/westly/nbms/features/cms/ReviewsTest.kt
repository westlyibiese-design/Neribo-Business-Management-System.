package com.westly.nbms.features.cms

import com.google.firebase.Timestamp
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastEvent
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import com.westly.nbms.core.feature.NavRules

private fun ts(seconds: Long) = Timestamp(seconds, 0)

private fun review(
    id: String,
    status: String = "pending",
    createdAt: Timestamp? = ts(1_000),
    rating: Int? = 5,
    name: String = "Guest $id"
) = Review(id, name, "Text of $id", rating, status, createdAt, null, null)

// ───────────────────────── reading, filtering, sorting ─────────────────────────

class ReviewsRulesTest {

    @Test fun parsesAWellFormedReview() {
        val r = parseReview(
            "r1",
            mapOf(
                "name" to "Ada", "text" to "Lovely stay", "rating" to 4L, "status" to "approved",
                "createdAt" to ts(10), "approvedAt" to ts(20), "approvedBy" to "u9"
            )
        )
        assertEquals(Review("r1", "Ada", "Lovely stay", 4, "approved", ts(10), ts(20), "u9"), r)
        assertTrue(r.isApproved)
        assertFalse(r.isPending)
    }

    @Test fun missingOrWronglyTypedFieldsAreTolerated() {
        val r = parseReview("r2", mapOf("name" to 5, "text" to null, "rating" to "five", "status" to 7, "createdAt" to "yesterday"))
        assertEquals("", r.name)
        assertEquals("", r.text)
        assertNull(r.rating)
        assertEquals("pending", r.status)
        assertNull(r.createdAt)
        assertNull(r.approvedAt)
        assertNull(r.approvedBy)
        assertTrue(r.isPending)
    }

    @Test fun aRatingOutsideOneToFiveIsNoRating() {
        assertNull(parseReview("a", mapOf("rating" to 0)).rating)
        assertNull(parseReview("a", mapOf("rating" to 6)).rating)
        assertNull(parseReview("a", mapOf("rating" to Double.NaN)).rating)
        assertEquals(1, parseReview("a", mapOf("rating" to 1)).rating)
        assertEquals(5, parseReview("a", mapOf("rating" to 5.0)).rating)
        assertNull(parseReview("a", emptyMap()).rating)
    }

    @Test fun anythingThatIsNotApprovedCountsAsPending() {
        assertTrue(ReviewsRules.isPending(review("a", status = "pending")))
        assertTrue(ReviewsRules.isPending(review("b", status = "rejected")))
        assertTrue(ReviewsRules.isPending(review("c", status = "")))
        assertTrue(ReviewsRules.isPending(review("d", status = "APPROVED")))
        assertFalse(ReviewsRules.isPending(review("e", status = "approved")))
        val all = listOf(review("a"), review("b", status = "weird"), review("c", status = "approved"))
        assertEquals(setOf("a", "b"), ReviewsRules.pending(all).map { it.id }.toSet())
        assertEquals(listOf("c"), ReviewsRules.approved(all).map { it.id })
    }

    @Test fun reviewsAreSortedNewestFirstAndOnesWithoutATimeGoLast() {
        val all = listOf(
            review("old", createdAt = ts(100)),
            review("none", createdAt = null),
            review("new", createdAt = ts(300)),
            review("mid", createdAt = ts(200))
        )
        assertEquals(listOf("new", "mid", "old", "none"), ReviewsRules.sortNewestFirst(all).map { it.id })
        assertEquals(listOf("new", "mid", "old", "none"), ReviewsRules.pending(all).map { it.id })
    }

    @Test fun nanosecondsBreakATieInTheSameSecond() {
        val all = listOf(review("a", createdAt = Timestamp(100, 1)), review("b", createdAt = Timestamp(100, 9)))
        assertEquals(listOf("b", "a"), ReviewsRules.sortNewestFirst(all).map { it.id })
    }

    @Test fun aNullRatingShowsNoStars() {
        assertNull(review("a", rating = null).rating)
        assertNull(parseReview("a", mapOf("rating" to null)).rating)
    }

    @Test fun deleteWordingAndNameFallbackMatchTheSpecification() {
        assertEquals(
            "Are you sure you want to delete the review from \"Ada\"? This will remove it from the public website immediately and can't be undone.",
            ReviewsRules.deleteBody("Ada")
        )
        assertEquals("Guest", ReviewsRules.displayName(review("a", name = "  ")))
        assertEquals("Ada", ReviewsRules.displayName(review("a", name = "Ada")))
    }

    @Test fun pageTextsMatchTheSpecification() {
        assertEquals("Guest Reviews", REVIEWS_TITLE)
        assertEquals("No reviews waiting for approval.", REVIEWS_EMPTY_PENDING)
        assertEquals("No approved reviews yet.", REVIEWS_EMPTY_APPROVED)
        assertEquals("Reviews failed to load. Reload and try again.", MSG_REVIEWS_LOAD_FAILED)
        assertEquals("Review Approved", TOAST_REVIEW_APPROVED_TITLE)
        assertEquals("It's now live on the public website.", TOAST_REVIEW_APPROVED_MESSAGE)
        assertEquals("Review Deleted", TOAST_REVIEW_DELETED)
    }
}

// ───────────────────────── fakes ─────────────────────────

internal class FakeReviewsSource(initial: List<Review> = emptyList()) : ReviewsSource {
    val flow = MutableStateFlow<Resource<List<Review>>>(Resource.Success(initial))
    val approved = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    var failWith: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override fun observe(): Flow<Resource<List<Review>>> = flow

    override suspend fun approve(id: String) {
        gate?.await()
        failWith?.let { throw it }
        approved += id
        val list = (flow.value as Resource.Success).data
        flow.value = Resource.Success(list.map { if (it.id == id) it.copy(status = "approved") else it })
    }

    override suspend fun delete(id: String) {
        gate?.await()
        failWith?.let { throw it }
        deleted += id
        val list = (flow.value as Resource.Success).data
        flow.value = Resource.Success(list.filter { it.id != id })
    }
}

internal class FakeReviewsStore : ReviewsStore {
    val flow = MutableStateFlow<Resource<List<Review>>>(Resource.Success(emptyList()))
    val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
    val deletes = mutableListOf<String>()
    var failWith: Exception? = null

    override fun observe(): Flow<Resource<List<Review>>> = flow

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        failWith?.let { throw it }
        updates += id to fields
    }

    override suspend fun delete(id: String) {
        failWith?.let { throw it }
        deletes += id
    }
}

internal class FakeReviewsSession(role: Role = Role.MANAGER, uid: String = "u1", signedIn: Boolean = true) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser(uid, "biz1", role, "Ada", "a@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

internal class FakeReviewsAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val id: String, val previous: Map<String, Any?>?, val new: Map<String, Any?>?)
    val entries = mutableListOf<Entry>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

// ───────────────────────── the repository ─────────────────────────

class ReviewsRepositoryTest {

    private class Rig(role: Role = Role.MANAGER, signedIn: Boolean = true) {
        val store = FakeReviewsStore()
        val audit = FakeReviewsAudit()
        val repo = ReviewsRepository(store, FakeReviewsSession(role, "u7", signedIn), audit)
    }

    @Test fun approvePayloadHoldsTheStatusTheServerTimeAndTheCurrentUser() {
        val p = buildReviewApprovePayload("u7")
        assertEquals(setOf("status", "approvedAt", "approvedBy"), p.keys)
        assertEquals("approved", p["status"])
        assertSame(ReviewServerTime, p["approvedAt"])
        assertEquals("u7", p["approvedBy"])
    }

    @Test fun approveUpdatesOnlyThoseThreeFieldsOfThatReview() = runTest {
        val rig = Rig()
        rig.repo.approve("r1")
        val (id, fields) = rig.store.updates.single()
        assertEquals("r1", id)
        assertEquals("approved", fields["status"])
        assertEquals("u7", fields["approvedBy"])
        assertSame(ReviewServerTime, fields["approvedAt"])
        assertEquals(3, fields.size)
        assertTrue(rig.store.deletes.isEmpty())
    }

    @Test fun approveAuditsReviewApprovedWithBeforeAndAfter() = runTest {
        val rig = Rig(Role.SUPER_ADMIN)
        rig.repo.approve("r1")
        val e = rig.audit.entries.single()
        assertEquals("review_approved", e.action)
        assertEquals("reviews", e.collection)
        assertEquals("r1", e.id)
        assertEquals(mapOf("status" to "pending"), e.previous)
        assertEquals(mapOf("status" to "approved"), e.new)
    }

    @Test fun deleteHardDeletesTheDocumentAndAuditsReviewDeleted() = runTest {
        val rig = Rig()
        rig.repo.delete("r2")
        assertEquals(listOf("r2"), rig.store.deletes)
        assertTrue(rig.store.updates.isEmpty())
        val e = rig.audit.entries.single()
        assertEquals("review_deleted", e.action)
        assertEquals("reviews", e.collection)
        assertEquals("r2", e.id)
    }

    @Test fun aFailedWriteThrowsWithAMessageAndWritesNoAuditEntry() = runTest {
        val rig = Rig()
        rig.store.failWith = IllegalStateException("You don't have access to this data.")
        try {
            rig.repo.approve("r1")
            fail("expected an exception")
        } catch (e: ReviewsException) {
            assertEquals("You don't have access to this data.", e.message)
        }
        try {
            rig.repo.delete("r1")
            fail("expected an exception")
        } catch (e: ReviewsException) {
            assertEquals("You don't have access to this data.", e.message)
        }
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun aFailingAuditNeverFailsTheChange() = runTest {
        val rig = Rig()
        rig.audit.fail = true
        rig.repo.approve("r1")
        rig.repo.delete("r2")
        assertEquals(1, rig.store.updates.size)
        assertEquals(1, rig.store.deletes.size)
    }

    @Test fun approvingWhileSignedOutIsRefusedAndWritesNothing() = runTest {
        val rig = Rig(signedIn = false)
        try {
            rig.repo.approve("r1")
            fail("expected an exception")
        } catch (e: ReviewsException) {
            assertEquals("You're signed out. Sign in again and retry.", e.message)
        }
        assertTrue(rig.store.updates.isEmpty())
        assertTrue(rig.audit.entries.isEmpty())
    }

    @Test fun observePassesTheStoreThrough() = runTest {
        val rig = Rig()
        rig.store.flow.value = Resource.Success(listOf(review("a")))
        assertEquals(listOf("a"), (rig.repo.observe().first() as Resource.Success).data.map { it.id })
        rig.store.flow.value = Resource.Error("boom")
        assertTrue(rig.repo.observe().first() is Resource.Error)
    }
}

// ───────────────────────── the ViewModel ─────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewsViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val toast = ToastController()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.toasts(): List<ToastEvent> {
        val events = mutableListOf<ToastEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.events.collect { events += it } }
        return events
    }

    private fun vm(source: ReviewsSource) = ReviewsViewModel(source, toast)

    // ── loading, filtering, sorting ──

    @Test fun splitsIntoPendingAndApprovedNewestFirst() = runTest {
        val source = FakeReviewsSource(
            listOf(
                review("p-old", createdAt = ts(100)),
                review("a-1", status = "approved", createdAt = ts(150)),
                review("p-new", createdAt = ts(300)),
                review("odd", status = "something else", createdAt = ts(200)),
                review("a-2", status = "approved", createdAt = ts(400))
            )
        )
        val s = vm(source).state.value
        assertFalse(s.loading)
        assertFalse(s.loadFailed)
        assertEquals(listOf("p-new", "odd", "p-old"), s.pending.map { it.id })
        assertEquals(listOf("a-2", "a-1"), s.approved.map { it.id })
    }

    @Test fun isLoadingUntilTheFirstAnswerAndNoReviewsIsNotAnError() = runTest {
        val source = FakeReviewsSource()
        source.flow.value = Resource.Loading
        val vm = vm(source)
        assertTrue(vm.state.value.loading)
        source.flow.value = Resource.Success(emptyList())
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loadFailed)
        assertTrue(vm.state.value.pending.isEmpty())
        assertTrue(vm.state.value.approved.isEmpty())
    }

    @Test fun aLoadErrorShowsTheBannerStateAndClearsWhenReviewsLoadAgain() = runTest {
        val source = FakeReviewsSource(listOf(review("a")))
        val vm = vm(source)
        source.flow.value = Resource.Error("offline")
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loading)
        source.flow.value = Resource.Success(listOf(review("a")))
        assertFalse(vm.state.value.loadFailed)
    }

    @Test fun aNewReviewFromAGuestShowsUpLiveAsPending() = runTest {
        val source = FakeReviewsSource()
        val vm = vm(source)
        source.flow.value = Resource.Success(listOf(review("fresh")))
        assertEquals(listOf("fresh"), vm.state.value.pending.map { it.id })
    }

    // ── approve ──

    @Test fun approveMovesTheReviewToApprovedAndShowsTheToast() = runTest {
        val source = FakeReviewsSource(listOf(review("r1"), review("r2")))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.approve("r1") { ok = it }
        assertTrue(ok)
        assertEquals(listOf("r1"), source.approved)
        assertEquals(listOf("r2"), vm.state.value.pending.map { it.id })
        assertEquals(listOf("r1"), vm.state.value.approved.map { it.id })
        val t = events.single()
        assertEquals("Review Approved", t.title)
        assertEquals("It's now live on the public website.", t.message)
        assertEquals(ToastType.Success, t.type)
        assertTrue(vm.state.value.busyIds.isEmpty())
    }

    @Test fun aFailedApproveShowsTheErrorToastAndFreesTheRow() = runTest {
        val source = FakeReviewsSource(listOf(review("r1")))
        source.failWith = ReviewsException("You don't have access to this data.")
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.approve("r1") { ok = it }
        assertFalse(ok)
        assertEquals("Error", events.single().title)
        assertEquals("You don't have access to this data.", events.single().message)
        assertEquals(ToastType.Error, events.single().type)
        assertTrue(vm.state.value.busyIds.isEmpty())
        assertEquals(1, vm.state.value.pending.size)

        source.failWith = null
        vm.approve("r1") { ok = it }
        assertTrue(ok)
    }

    // ── delete ──

    @Test fun deleteRemovesTheReviewAndShowsReviewDeleted() = runTest {
        val source = FakeReviewsSource(listOf(review("r1"), review("r2", status = "approved")))
        val vm = vm(source)
        val events = toasts()
        var ok = false
        vm.delete("r2") { ok = it }
        assertTrue(ok)
        assertEquals(listOf("r2"), source.deleted)
        assertTrue(vm.state.value.approved.isEmpty())
        assertEquals(1, vm.state.value.pending.size)
        assertEquals("Review Deleted", events.single().message)
        assertEquals(ToastType.Success, events.single().type)
    }

    @Test fun aFailedDeleteShowsTheErrorToastAndKeepsTheReview() = runTest {
        val source = FakeReviewsSource(listOf(review("r1")))
        source.failWith = IllegalStateException("offline")
        val vm = vm(source)
        val events = toasts()
        var ok = true
        vm.delete("r1") { ok = it }
        assertFalse(ok)
        assertEquals("Error", events.single().title)
        assertEquals(1, vm.state.value.pending.size)
        assertTrue(vm.state.value.busyIds.isEmpty())
    }

    // ── per-row busy flags ──

    @Test fun aSecondTapOnABusyRowIsIgnoredButAnotherRowCanBeHandled() = runTest {
        val source = FakeReviewsSource(listOf(review("r1"), review("r2")))
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val vm = vm(source)
        val events = toasts()

        val results = mutableListOf<Boolean>()
        vm.approve("r1") { results += it }
        assertEquals(setOf("r1"), vm.state.value.busyIds)

        vm.approve("r1") { results += it }      // double tap on the same row
        vm.delete("r1") { results += it }       // a different action on the same busy row
        assertEquals(listOf(false, false), results)
        assertTrue(events.isEmpty())

        vm.approve("r2") { results += it }      // another row has its own flag
        assertEquals(setOf("r1", "r2"), vm.state.value.busyIds)

        gate.complete(Unit)
        assertEquals(listOf(false, false, true, true), results)
        assertTrue(vm.state.value.busyIds.isEmpty())
        assertEquals(listOf("r1", "r2"), source.approved)
        assertTrue(source.deleted.isEmpty())
        assertEquals(2, events.size)
    }

    @Test fun twoRowsCanBeHandledOneAfterTheOther() = runTest {
        val source = FakeReviewsSource(listOf(review("r1"), review("r2")))
        val vm = vm(source)
        var first = false
        var second = false
        vm.approve("r1") { first = it }
        vm.delete("r2") { second = it }
        assertTrue(first)
        assertTrue(second)
        assertEquals(listOf("r1"), source.approved)
        assertEquals(listOf("r2"), source.deleted)
    }

    // ── registration ──

    @Test fun theFeatureRegistersTheThreeCmsRoutesForSuperAdminAndManager() {
        val feature = CmsFeature()
        assertEquals("cms", feature.id)
        assertEquals(listOf("facilities", "gallery", "reviews"), feature.screens.map { it.route })
        assertEquals(listOf("facilities", "gallery", "reviews"), feature.nav.map { it.route })
        assertEquals(listOf("Facilities", "Gallery", "Guest Reviews"), feature.nav.map { it.label })
        assertEquals(listOf(260, 280, 290), feature.nav.map { it.order })
        feature.nav.forEach {
            assertNull(it.group)
            assertNull(it.module)
            assertEquals(setOf(Role.SUPER_ADMIN, Role.MANAGER), it.roles)
        }
    }

    @Test fun theRouteGuardAgreesWithTheNavEntries() {
        for (route in listOf("gallery", "reviews")) {
            assertTrue(NavRules.canOpen(route, Role.MANAGER, emptySet()))
            assertTrue(NavRules.canOpen(route, Role.SUPER_ADMIN, emptySet()))
            assertFalse(NavRules.canOpen(route, Role.RECEPTIONIST, emptySet()))
            assertFalse(NavRules.canOpen(route, Role.ACCOUNTANT, emptySet()))
        }
    }
}
