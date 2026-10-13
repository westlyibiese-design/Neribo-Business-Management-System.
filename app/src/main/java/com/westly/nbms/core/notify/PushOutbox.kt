package com.westly.nbms.core.notify

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.TimeUnit

private const val TAG = "PushOutbox"
private const val KEY_BODY = "body"
/** WorkManager input data is limited to 10 KB. */
private const val MAX_BODY_CHARS = 9_000
private const val MAX_ATTEMPTS = 12

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PushOutboxEntryPoint {
    fun supabase(): SupabaseClient
}

/**
 * Push messages that could not be sent because the phone had no internet. Each one is kept by the system and sent
 * once the internet is back, even if the app was closed in between. Every message has its own unique name (the
 * notification id), so the same message can never be queued - or sent - twice.
 * (A phone that is offline when a push is sent still receives it later: Google keeps it for that phone.)
 */
object PushOutbox {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun enqueue(notificationId: String, body: JsonObject) {
        val context = appContext ?: return
        val raw = body.toString()
        if (raw.length > MAX_BODY_CHARS) {
            Log.w(TAG, "Push request too large to queue; skipped")
            return
        }
        try {
            val request = OneTimeWorkRequestBuilder<PushOutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_BODY to raw))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("push-$notificationId", ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.w(TAG, "Could not queue push: ${e.javaClass.simpleName}")
        }
    }
}

class PushOutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val raw = inputData.getString(KEY_BODY) ?: return Result.failure()
        val body = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return Result.failure()
        }
        val supabase = EntryPointAccessors
            .fromApplication(applicationContext, PushOutboxEntryPoint::class.java)
            .supabase()
        return try {
            val response = supabase.functions.invoke(function = "send-push", body = body)
            if (response.status.isSuccess()) Result.success() else retryOrStop(response.status.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            retryOrStop(e.statusCode)
        } catch (e: Exception) {
            retryOrStop(0)
        }
    }

    /** A request the server will never accept (bad data) is dropped; anything else is tried again later. */
    private fun retryOrStop(status: Int): Result {
        val permanent = status in 400..499 && status != 401 && status != 408 && status != 429
        return if (permanent || runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
    }
}
