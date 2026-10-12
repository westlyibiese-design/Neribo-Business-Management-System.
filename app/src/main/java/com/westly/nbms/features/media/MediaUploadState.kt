package com.westly.nbms.features.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How far a running upload queue has got. [index] is 1-based while [active]. */
data class UploadProgress(val active: Boolean = false, val index: Int = 0, val total: Int = 0)

/** The result of one finished queue. */
data class UploadSummary(val succeeded: Int, val failed: Int, val firstError: String?)

/**
 * One upload queue for ONE photo field. Pictures are uploaded one after another (never in parallel);
 * each success is reported at once so a partly finished batch is kept. A second [start] while a queue is running is
 * ignored. After [discard] (the field left the screen) the running queue still finishes, but nothing is reported.
 *
 * [S] is what the person picked (a Uri in the app, plain text in tests); [uploadOne] turns it into a public url.
 */
class MediaUploadState<S>(
    private val scope: CoroutineScope,
    private val uploadOne: suspend (S) -> String
) {
    private val _progress = MutableStateFlow(UploadProgress())
    val progress: StateFlow<UploadProgress> = _progress.asStateFlow()

    @Volatile
    private var discarded = false

    /**
     * Starts a queue. Returns false (and does nothing) when a queue is already running or [items] is empty.
     * [onEach] gets every url as soon as it is uploaded; [onFinished] gets the summary after the last item.
     */
    fun start(items: List<S>, onEach: (String) -> Unit, onFinished: (UploadSummary) -> Unit): Boolean {
        if (items.isEmpty()) return false
        if (!_progress.compareAndSet(UploadProgress(), UploadProgress(active = true, index = 1, total = items.size))) {
            return false
        }
        scope.launch {
            var succeeded = 0
            var failed = 0
            var firstError: String? = null
            try {
                items.forEachIndexed { position, item ->
                    _progress.value = UploadProgress(active = true, index = position + 1, total = items.size)
                    try {
                        val url = uploadOne(item)
                        succeeded++
                        if (!discarded) onEach(url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        if (firstError == null) firstError = e.message?.takeIf { it.isNotBlank() } ?: MSG_MEDIA_UPLOAD_FAILED
                    }
                }
            } finally {
                _progress.value = UploadProgress()
            }
            if (!discarded) onFinished(UploadSummary(succeeded, failed, firstError))
        }
        return true
    }

    /** The field left the screen: let a running queue finish quietly, report nothing more. */
    fun discard() {
        discarded = true
    }
}
