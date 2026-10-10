package com.westly.nbms.features.housekeeping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

internal const val RUN_QUEUE_TITLE_OK = "Queue Updated"
internal const val RUN_QUEUE_TITLE_ERROR = "Error"
internal const val RUN_QUEUE_FAILED = "Failed to run queue generator."

/** What one "Run Queue Now" did, read from the server's reply. */
internal data class RunQueueCounts(val checkout: Int, val occupied: Int, val rebalanced: Int)

/** "{a} checkout + {b} occupied-service task(s) created{ (k rebalanced for fairness)}." */
internal fun runQueueSuccessMessage(c: RunQueueCounts): String {
    val rebalanced = if (c.rebalanced > 0) " (${c.rebalanced} rebalanced for fairness)" else ""
    return "${c.checkout} checkout + ${c.occupied} occupied-service task(s) created$rebalanced."
}

private val errorPattern = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** Pulls the `error` text out of a server reply such as {"ok":false,"error":"..."}; null when there is none. */
internal fun runQueueServerError(vararg candidates: String?): String? {
    for (c in candidates) {
        if (c.isNullOrBlank()) continue
        errorPattern.find(c)?.let { return it.groupValues[1].replace("\\\"", "\"") }
    }
    return null
}

/** Reads the counts from {"ok":true, checkoutTasksCreated, occupiedServiceTasksCreated, rebalancedCount, ...}. */
internal fun parseRunQueueReply(text: String): Result<RunQueueCounts> {
    val obj: JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        return Result.failure(Exception(RUN_QUEUE_FAILED))
    }
    val okFlag = (obj["ok"] as? JsonPrimitive)?.content
    if (okFlag == "false") {
        return Result.failure(Exception(runQueueServerError(text) ?: RUN_QUEUE_FAILED))
    }
    fun count(key: String): Int = (obj[key] as? JsonPrimitive)?.intOrNull ?: 0
    return Result.success(
        RunQueueCounts(
            checkout = count("checkoutTasksCreated"),
            occupied = count("occupiedServiceTasksCreated"),
            rebalanced = count("rebalancedCount")
        )
    )
}

/**
 * Phase 24: the outlined "Run Queue Now" button on the management Housekeeping Overview.
 * Shown only to Super Admin, Manager and Operations Manager. It calls the Edge Function `housekeeping-queue-run`
 * with the signed-in user's Supabase session, which builds the queue for this business only.
 */
class RunQueueAction @Inject constructor(
    private val supabase: SupabaseClient,
    private val toast: ToastController
) : HousekeepingOverviewAction {

    @Composable
    override fun Content(session: SessionState.SignedIn) {
        if (!canActOnOverview(session.user.role)) return
        var running by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        NbmsButton(
            text = "Run Queue Now",
            onClick = {
                if (!running) {
                    running = true
                    scope.launch {
                        try {
                            execute()
                        } finally {
                            running = false
                        }
                    }
                }
            },
            variant = ButtonVariant.Outline,
            leadingIcon = NbmsIcons.Clock,
            loading = running,
            enabled = !running
        )
    }

    private suspend fun execute() {
        val result = call()
        result.onSuccess { counts ->
            toast.show(runQueueSuccessMessage(counts), ToastType.Success, RUN_QUEUE_TITLE_OK)
        }.onFailure { e ->
            toast.show(e.message ?: RUN_QUEUE_FAILED, ToastType.Error, RUN_QUEUE_TITLE_ERROR)
        }
    }

    private suspend fun call(): Result<RunQueueCounts> = try {
        val response = supabase.functions.invoke(function = "housekeeping-queue-run", body = buildJsonObject { })
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            parseRunQueueReply(text)
        } else {
            Result.failure(Exception(runQueueServerError(text) ?: RUN_QUEUE_FAILED))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        Result.failure(Exception(runQueueServerError(e.description, e.message, e.error) ?: RUN_QUEUE_FAILED))
    } catch (e: Exception) {
        Result.failure(Exception(RUN_QUEUE_FAILED))
    }
}
