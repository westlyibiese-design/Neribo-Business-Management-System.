package com.westly.nbms.features.messages

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Quiet time after the last change event before the inbox is fetched again. */
const val REFRESH_DEBOUNCE_MS = 400L

/**
 * Runs [action] once for each burst of events: after [windowMs] without a new event.
 * Several events inside the window cause one call. Used to turn realtime events into one re-fetch.
 */
@OptIn(FlowPreview::class)
suspend fun Flow<Unit>.collectDebounced(windowMs: Long = REFRESH_DEBOUNCE_MS, action: suspend () -> Unit) {
    debounce(windowMs).collect { action() }
}

/**
 * Emits [Unit] whenever a row of `public.messages` of this business is inserted, updated or deleted.
 *
 * - One channel (`messages-inbox`) is open at a time. A second collector for the same business shares
 *   the open subscription; a different business waits until the first has fully closed.
 * - The channel is closed and removed when the last collector goes away or the collector is cancelled
 *   (sign-out).
 * - If subscribing fails the flow simply stays quiet (no error is shown); the screen still refreshes
 *   by pull-down and when it is opened again.
 */
@Singleton
class MessagesRealtime @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shared = ConcurrentHashMap<String, Flow<Unit>>()
    private val gate = Mutex()

    fun changes(businessId: String): Flow<Unit> =
        shared.getOrPut(businessId) {
            subscription(businessId).shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)
        }

    private fun subscription(businessId: String): Flow<Unit> = channelFlow {
        // The lock is held for the whole life of the subscription, so two channels with the same topic never overlap.
        gate.withLock {
            val channel = supabase.channel(TOPIC)
            try {
                val events = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "messages"
                    filter("business_id", FilterOperator.EQ, businessId)
                }
                launch { events.collect { send(Unit) } }
                channel.subscribe(blockUntilSubscribed = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent fallback: nothing is emitted, the lock is released when the collector goes away.
            }
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    try {
                        channel.unsubscribe()
                    } catch (e: Exception) {
                        // already closed
                    }
                    try {
                        supabase.realtime.removeChannel(channel)
                    } catch (e: Exception) {
                        // already removed
                    }
                }
            }
        }
    }

    companion object {
        const val TOPIC = "messages-inbox"
    }
}
