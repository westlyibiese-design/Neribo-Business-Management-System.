package com.westly.nbms.features.notifications

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.AppNotification
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Notifications"

/** How many notifications each listener loads: 60 for the bell, 200 for the page. */
const val BELL_LIMIT = 60
const val PAGE_LIMIT = 200

/** What the live feed is doing right now. */
sealed interface FeedState {
    data object Loading : FeedState
    data class Error(val message: String) : FeedState
    /** [items] are already filtered for this person and sorted newest first. */
    data class Ready(val items: List<AppNotification>) : FeedState
}

// ---------- Pure rules (unit tested) ----------

private fun sortKey(n: AppNotification): Long =
    // A notification that was just written has no server time yet; it is the newest.
    n.createdAt?.let { it.seconds * 1_000L + it.nanoseconds / 1_000_000 } ?: Long.MAX_VALUE

/** Merges lists by document id (the first copy wins) and sorts newest first. */
internal fun mergeFeed(vararg lists: List<AppNotification>): List<AppNotification> =
    lists.flatMap { it }
        .distinctBy { it.id }
        .sortedWith(compareByDescending<AppNotification> { sortKey(it) }.thenBy { it.id })

/**
 * Drops what this person must not see: removed-for-me, sent-by-me-with-exclude, and notifications aimed at
 * specific people that do not include me (the Super Admin sees those anyway).
 */
internal fun visibleFor(items: List<AppNotification>, uid: String, isSuperAdmin: Boolean): List<AppNotification> =
    items.filter { n ->
        uid !in n.deletedBy &&
            n.excludeUserId != uid &&
            (n.forUserIds.isEmpty() || uid in n.forUserIds || isSuperAdmin)
    }

internal fun isUnread(n: AppNotification, uid: String): Boolean = uid !in n.readBy

internal fun unreadCountOf(items: List<AppNotification>, uid: String): Int = items.count { isUnread(it, uid) }

/**
 * Live notifications for the signed-in person.
 *
 * Two Firestore listeners are merged by id (one query cannot OR two array fields):
 * 1. Super Admin: everything; other roles: `forRoles array-contains {role}`.
 * 2. Everyone except Super Admin: `forUserIds array-contains {uid}`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NotificationsRepository @Inject constructor(
    private val firestore: BusinessFirestore,
    private val session: SessionManager
) {

    private val signedIn: Flow<SessionState.SignedIn?> = session.state
        .map { it as? SessionState.SignedIn }
        .distinctUntilChanged { a, b ->
            a?.user?.uid == b?.user?.uid && a?.business?.id == b?.business?.id && a?.user?.role == b?.user?.role
        }

    /** The page feed (up to [PAGE_LIMIT] per listener), empty while loading or on error. */
    val notifications: Flow<List<AppNotification>> =
        feedState(PAGE_LIMIT).map { (it as? FeedState.Ready)?.items ?: emptyList() }

    /** Unread items in the bell feed (up to [BELL_LIMIT] per listener). */
    val unreadCount: Flow<Int> = signedIn.flatMapLatest { s ->
        if (s == null) flowOf(0)
        else feedState(BELL_LIMIT).map { st -> (st as? FeedState.Ready)?.let { unreadCountOf(it.items, s.user.uid) } ?: 0 }
    }.distinctUntilChanged()

    fun feedState(limit: Int): Flow<FeedState> = signedIn.flatMapLatest { s ->
        if (s == null) flowOf(FeedState.Ready(emptyList())) else buildFeed(s, limit)
    }

    private fun buildFeed(s: SessionState.SignedIn, limit: Int): Flow<FeedState> {
        val uid = s.user.uid
        val isSuperAdmin = s.user.role == Role.SUPER_ADMIN

        val first: Flow<Resource<List<AppNotification>>> = firestore.observeList("notifications", AppNotification::class.java) { q ->
            val base = if (isSuperAdmin) q else q.whereArrayContains("forRoles", s.user.role.key)
            base.orderBy("createdAt", Query.Direction.DESCENDING).limit(limit.toLong())
        }
        if (isSuperAdmin) {
            return first.map { r -> r.toFeedState { visibleFor(mergeFeed(it), uid, true) } }
        }
        val second: Flow<Resource<List<AppNotification>>> = firestore.observeList("notifications", AppNotification::class.java) { q ->
            q.whereArrayContains("forUserIds", uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(limit.toLong())
        }
        return combine(first, second) { a, b ->
            when {
                a is Resource.Error -> FeedState.Error(a.message)
                b is Resource.Error -> FeedState.Error(b.message)
                a is Resource.Success && b is Resource.Success ->
                    FeedState.Ready(visibleFor(mergeFeed(a.data, b.data), uid, false))
                else -> FeedState.Loading
            }
        }
    }

    private inline fun Resource<List<AppNotification>>.toFeedState(
        transform: (List<AppNotification>) -> List<AppNotification>
    ): FeedState = when (this) {
        is Resource.Success -> FeedState.Ready(transform(data))
        is Resource.Error -> FeedState.Error(message)
        Resource.Loading -> FeedState.Loading
    }

    private fun currentUid(): String =
        (session.state.value as? SessionState.SignedIn)?.user?.uid ?: throw IllegalStateException("Not signed in")

    suspend fun markRead(id: String): Result<Unit> = guarded("markRead") {
        firestore.update("notifications", id, mapOf("readBy" to FieldValue.arrayUnion(currentUid())))
    }

    /** Marks every unread item in the page feed as read. */
    suspend fun markAllRead(): Result<Unit> = guarded("markAllRead") {
        val uid = currentUid()
        val ready = withTimeoutOrNull(15_000) { feedState(PAGE_LIMIT).filterIsInstance<FeedState.Ready>().first() }
            ?: throw IllegalStateException("Notifications are not loaded yet.")
        val unread = ready.items.filter { isUnread(it, uid) }
        unread.chunked(400).forEach { chunk ->
            val col = firestore.collection("notifications")
            val batch = col.firestore.batch()
            chunk.forEach { batch.update(col.document(it.id), "readBy", FieldValue.arrayUnion(uid)) }
            batch.commit().await()
        }
    }

    /** Hides a notification for me only; the document stays for everyone else. */
    suspend fun removeForMe(id: String): Result<Unit> = guarded("removeForMe") {
        firestore.update("notifications", id, mapOf("deletedBy" to FieldValue.arrayUnion(currentUid())))
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        Result.failure(e)
    }
}
