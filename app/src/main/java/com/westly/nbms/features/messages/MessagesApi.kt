package com.westly.nbms.features.messages

import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

internal const val MSG_MESSAGES_NETWORK = "Can't reach the server. Check your connection and try again."
internal const val MSG_MESSAGES_NOT_ALLOWED = "Your role can't open the message inbox."

/** Only these roles may call `messages-list` and `messages-update`. */
internal val MESSAGE_ROLES: Set<Role> = setOf(Role.SUPER_ADMIN, Role.MANAGER, Role.RECEPTIONIST)

private val errorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** Pulls the `error` text out of a server reply such as {"ok":false,"error":"..."}; null when there is none. */
internal fun messagesServerError(vararg candidates: String?): String? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        errorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
    }
    return null
}

/**
 * The inbox's one source of truth. The screen and the drawer badge both read it, so the count is the same
 * everywhere. All server calls go through the Edge Functions `messages-list` and `messages-update`.
 *
 * Only super_admin, manager and receptionist ever reach the server; for any other role every call
 * answers with a failure, nothing is sent and [unreadCount] stays 0.
 */
@Singleton
class MessagesApi @Inject constructor(
    private val supabase: SupabaseClient,
    private val session: SessionManager,
    private val realtime: MessagesRealtime
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _messages = MutableStateFlow<List<InboxMessage>>(emptyList())
    /** Newest first, deleted ones left out. */
    val messages: StateFlow<List<InboxMessage>> = _messages.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    /** Shared by the screen header and the drawer badge. 0 when signed out. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val listLock = Mutex()
    private val trackerLock = Any()
    private var tracker: Job? = null

    private fun allowed(): Boolean {
        val s = session.state.value
        return s is SessionState.SignedIn && s.user.role in MESSAGE_ROLES
    }

    private fun publish(list: List<InboxMessage>) {
        _messages.value = list
        _unreadCount.value = unreadOf(list)
    }

    /** Fetches the inbox. On success the shared list and the unread count are updated. */
    suspend fun list(): Result<List<InboxMessage>> {
        if (!allowed()) return Result.failure(Exception(MSG_MESSAGES_NOT_ALLOWED))
        return listLock.withLock {
            call("messages-list", buildJsonObject { }, ::parseMessagesReply, MSG_LOAD_GENERIC).also { result ->
                result.onSuccess { if (allowed()) publish(it) }
            }
        }
    }

    /**
     * Marks a message read. The shared list changes first, so a card never flickers back to unread;
     * a failed call keeps it read on screen.
     */
    suspend fun markRead(id: String): Result<Unit> {
        if (!allowed()) return Result.failure(Exception(MSG_MESSAGES_NOT_ALLOWED))
        publish(markReadLocal(_messages.value, id, Clock.System.now()))
        return update(buildJsonObject {
            put("id", id)
            put("action", "mark_read")
        })
    }

    suspend fun setReplyStatus(id: String, status: ReplyStatus): Result<Unit> {
        if (!allowed()) return Result.failure(Exception(MSG_MESSAGES_NOT_ALLOWED))
        return update(buildJsonObject {
            put("id", id)
            put("action", "set_reply_status")
            put("replyStatus", status.wire)
        }).onSuccess { publish(setReplyLocal(_messages.value, id, status, Clock.System.now())) }
    }

    /** Soft delete: the row stays in Supabase with `is_deleted = true`. */
    suspend fun softDelete(id: String): Result<Unit> {
        if (!allowed()) return Result.failure(Exception(MSG_MESSAGES_NOT_ALLOWED))
        return update(buildJsonObject {
            put("id", id)
            put("action", "soft_delete")
        }).onSuccess { publish(removeLocal(_messages.value, id)) }
    }

    private suspend fun update(body: JsonObject): Result<Unit> =
        call("messages-update", body, ::parseUpdateReply, MSG_UPDATE_GENERIC)

    private suspend fun <T> call(
        function: String,
        body: JsonObject,
        parse: (String) -> Result<T>,
        generic: String
    ): Result<T> {
        return try {
            val response = supabase.functions.invoke(function = function, body = body)
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                parse(text)
            } else {
                Result.failure(Exception(messagesServerError(text) ?: generic))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            Result.failure(Exception(messagesServerError(e.description, e.message, e.error) ?: generic))
        } catch (e: Exception) {
            Result.failure(Exception(MSG_MESSAGES_NETWORK))
        }
    }

    // ------------------------------------------------------------ app-scoped tracker (drawer badge + live refresh)

    /**
     * Starts, once, the tracker that keeps the inbox and the unread count current: an initial fetch, then a
     * re-fetch (debounced 400 ms) after every change on `public.messages` for this business.
     * Safe to call again and again; the screen's ViewModel and the drawer badge share this one tracker.
     * It follows the session: a different business restarts it, sign-out stops it and resets everything to empty.
     */
    fun ensureTracking() {
        synchronized(trackerLock) {
            if (tracker?.isActive == true) return
            tracker = scope.launch {
                session.state
                    .map { s -> (s as? SessionState.SignedIn)?.takeIf { it.user.role in MESSAGE_ROLES }?.user?.businessId }
                    .distinctUntilChanged()
                    .collectLatest { businessId ->
                        if (businessId == null) {
                            publish(emptyList())
                        } else {
                            list()
                            realtime.changes(businessId).collectDebounced { list() }
                        }
                    }
            }
        }
    }
}
