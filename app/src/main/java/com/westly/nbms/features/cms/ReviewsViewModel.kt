package com.westly.nbms.features.cms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.comparisons.nullsFirst
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

internal const val TOAST_REVIEW_APPROVED_TITLE = "Review Approved"
internal const val TOAST_REVIEW_APPROVED_MESSAGE = "It's now live on the public website."
internal const val TOAST_REVIEW_DELETED = "Review Deleted"

/** The plain rules of the Guest Reviews page (UI-free so they can be tested alone). */
object ReviewsRules {
    /** Anything whose status is not "approved" is pending. */
    fun isPending(review: Review): Boolean = review.status != REVIEW_STATUS_APPROVED

    /** Newest first by `createdAt`; a review without a time goes last. Ties keep a stable order by id. */
    fun sortNewestFirst(reviews: List<Review>): List<Review> =
        reviews.sortedWith(
            compareByDescending<Review, Timestamp?>(nullsFirst<Timestamp>()) { it.createdAt }.thenBy { it.id }
        )

    fun pending(reviews: List<Review>): List<Review> = sortNewestFirst(reviews.filter { isPending(it) })

    fun approved(reviews: List<Review>): List<Review> = sortNewestFirst(reviews.filter { !isPending(it) })

    fun displayName(review: Review): String = review.name.ifBlank { "Guest" }

    fun deleteBody(name: String): String =
        "Are you sure you want to delete the review from \"$name\"? This will remove it from the public website immediately and can't be undone."
}

data class ReviewsUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val pending: List<Review> = emptyList(),
    val approved: List<Review> = emptyList(),
    /** Reviews being approved or deleted right now. Each row has its own flag. */
    val busyIds: Set<String> = emptySet()
)

/**
 * The state and the two changes of the Guest Reviews page. UI-free. Every review has its own busy flag, so two rows can be
 * handled one after the other, while a second tap on the same row (busy) is ignored.
 */
@HiltViewModel
class ReviewsViewModel internal constructor(
    private val source: ReviewsSource,
    private val toast: ToastController
) : ViewModel() {

    @Inject
    constructor(repository: ReviewsRepository, toast: ToastController) : this(repository as ReviewsSource, toast)

    private val _state = MutableStateFlow(ReviewsUiState())
    val state: StateFlow<ReviewsUiState> = _state.asStateFlow()

    /** Synchronous claim on a row, so a fast double tap can never start two changes. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        viewModelScope.launch {
            source.observe()
                .catch { _state.update { s -> s.copy(loading = false, loadFailed = true) } }
                .collect { resource ->
                    when (resource) {
                        is Resource.Loading -> Unit
                        is Resource.Error -> _state.update { it.copy(loading = false, loadFailed = true) }
                        is Resource.Success -> _state.update {
                            it.copy(
                                loading = false,
                                loadFailed = false,
                                pending = ReviewsRules.pending(resource.data),
                                approved = ReviewsRules.approved(resource.data)
                            )
                        }
                    }
                }
        }
    }

    private fun claim(id: String): Boolean {
        if (!busy.add(id)) return false
        _state.update { it.copy(busyIds = it.busyIds + id) }
        return true
    }

    private fun release(id: String) {
        _state.update { it.copy(busyIds = it.busyIds - id) }
        busy.remove(id)
    }

    private fun perform(id: String, block: suspend () -> Unit, onSuccess: () -> Unit, onDone: (Boolean) -> Unit) {
        if (!claim(id)) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            var ok = false
            try {
                block()
                onSuccess()
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: "Something went wrong. Please try again.", type = ToastType.Error, title = TITLE_ERROR)
            } finally {
                release(id)
            }
            onDone(ok)
        }
    }

    /** Approves the review. Toast "Review Approved" / "It's now live on the public website." */
    fun approve(id: String, onDone: (Boolean) -> Unit = {}) {
        perform(
            id,
            block = { source.approve(id) },
            onSuccess = { toast.show(message = TOAST_REVIEW_APPROVED_MESSAGE, type = ToastType.Success, title = TOAST_REVIEW_APPROVED_TITLE) },
            onDone = onDone
        )
    }

    /** Hard-deletes the review. Toast "Review Deleted". */
    fun delete(id: String, onDone: (Boolean) -> Unit = {}) {
        perform(
            id,
            block = { source.delete(id) },
            onSuccess = { toast.show(message = TOAST_REVIEW_DELETED, type = ToastType.Success) },
            onDone = onDone
        )
    }
}
