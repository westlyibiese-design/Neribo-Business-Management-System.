package com.westly.nbms.features.cms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ImageFieldProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal const val DOC_GALLERY = "gallery"
internal const val AUDIT_GALLERY_UPDATED = "gallery_updated"
internal const val GALLERY_IMAGE_FOLDER = "gallery"

internal const val MSG_GALLERY_LOAD_FAILED_SAVE =
    "The gallery failed to load, so saving now could overwrite it with incomplete data. Reload the page first."
internal const val MSG_GALLERY_STILL_LOADING = "The gallery is still loading. Try again in a moment."
internal const val MSG_GALLERY_TITLE_IMAGE = "Title and image are required."
internal const val MSG_GALLERY_LIMIT = "You've reached the maximum of 100 images. Delete one to add another."
internal const val TOAST_IMAGE_ADDED = "Image Added"
internal const val TOAST_IMAGE_UPDATED = "Image Updated"
internal const val TOAST_IMAGE_DELETED = "Image Deleted"

/** The way a gallery image is read, written and identified inside `cms_content/gallery`. */
internal val GALLERY_CODEC: ListCodec<GalleryItem> = ListCodec(
    parse = { GalleryItem.parseList(it) },
    toMap = { it.toMap() },
    idOf = { it.id }
)

/** The plain rules of the Gallery page (UI-free so they can be tested alone). */
object GalleryRules {
    const val MAX = CmsLimits.GALLERY

    /** A title and an image are both required. */
    fun isValid(title: String, imageUrl: String): Boolean = title.isNotBlank() && imageUrl.isNotBlank()

    fun canAdd(count: Int): Boolean = count < MAX

    fun heading(count: Int): String = "Photos ($count/$MAX)"

    fun deleteBody(title: String): String = "Are you sure you want to delete \"$title\"? This will remove it from the public website immediately."
}

data class GalleryUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val images: List<GalleryItem> = emptyList(),
    val saving: Boolean = false
)

/**
 * The state and every change of the Gallery list. UI-free. Each change is one list operation sent to
 * [CmsSource.mutateList], so another person's edit of a different image is never lost. A refused call (still loading,
 * load failed, already saving, bad input, limit reached) writes nothing and calls `onDone(false)`.
 */
@HiltViewModel
class GalleryViewModel internal constructor(
    private val source: CmsSource,
    private val toast: ToastController,
    val imageProviders: Set<ImageFieldProvider>
) : ViewModel() {

    @Inject
    constructor(
        repository: CmsRepository,
        toast: ToastController,
        imageProviders: @JvmSuppressWildcards Set<ImageFieldProvider>
    ) : this(repository as CmsSource, toast, imageProviders)

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private val guard = CmsSaveGuard()

    init {
        viewModelScope.launch {
            source.observeDoc(DOC_GALLERY)
                .catch { _state.update { s -> s.copy(loading = false, loadFailed = true) } }
                .collect { resource ->
                    when (resource) {
                        is Resource.Loading -> Unit
                        is Resource.Error -> _state.update { it.copy(loading = false, loadFailed = true) }
                        is Resource.Success -> _state.update {
                            it.copy(loading = false, loadFailed = false, images = GalleryItem.parseList(resource.data.data))
                        }
                    }
                }
        }
    }

    private fun refuse(title: String, message: String, onDone: (Boolean) -> Unit) {
        toast.show(message = message, type = ToastType.Error, title = title)
        onDone(false)
    }

    /** Load-error guard, still loading, already saving — in that order. True means the caller has taken the saving flag. */
    private fun begin(onDone: (Boolean) -> Unit): Boolean {
        val s = _state.value
        if (s.loadFailed) {
            refuse(TITLE_CANT_SAVE_YET, MSG_GALLERY_LOAD_FAILED_SAVE, onDone)
            return false
        }
        if (s.loading) {
            refuse(TITLE_NOT_SAVED, MSG_GALLERY_STILL_LOADING, onDone)
            return false
        }
        if (!guard.tryStart()) {
            onDone(false)
            return false
        }
        _state.update { it.copy(saving = true) }
        return true
    }

    private fun release() {
        _state.update { it.copy(saving = false) }
        guard.finish()
    }

    private fun send(op: ListOp<GalleryItem>, successToast: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            var ok = false
            try {
                when (source.mutateList(DOC_GALLERY, op, GALLERY_CODEC, CmsLimits.GALLERY, AUDIT_GALLERY_UPDATED)) {
                    MutateResult.Done -> {
                        toast.show(message = successToast, type = ToastType.Success)
                        ok = true
                    }
                    MutateResult.AlreadyChanged -> toast.show(message = MSG_ALREADY_CHANGED, type = ToastType.Error)
                    MutateResult.LimitReached -> toast.show(message = MSG_GALLERY_LIMIT, type = ToastType.Error, title = TITLE_NOT_SAVED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: "Something went wrong. Please try again.", type = ToastType.Error, title = TITLE_ERROR)
            } finally {
                release()
            }
            onDone(ok)
        }
    }

    /** Adds an image ([id] null or blank) or replaces the one with that [id]. Title and image are required; title and caption are trimmed. */
    fun saveImage(id: String?, title: String, imageUrl: String, caption: String, onDone: (Boolean) -> Unit = {}) {
        if (!begin(onDone)) return
        val cleanTitle = title.trim()
        val cleanUrl = imageUrl.trim()
        val cleanCaption = caption.trim()
        if (!GalleryRules.isValid(cleanTitle, cleanUrl)) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_GALLERY_TITLE_IMAGE, onDone)
            return
        }
        val isNew = id.isNullOrBlank()
        if (isNew && !GalleryRules.canAdd(_state.value.images.size)) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_GALLERY_LIMIT, onDone)
            return
        }
        val item = GalleryItem(
            id = if (isNew) CmsIds.newId() else id!!.trim(),
            title = cleanTitle,
            caption = cleanCaption,
            imageUrl = cleanUrl
        )
        if (isNew) send(ListOp.Add(item), TOAST_IMAGE_ADDED, onDone)
        else send(ListOp.Replace(item), TOAST_IMAGE_UPDATED, onDone)
    }

    fun deleteImage(id: String, onDone: (Boolean) -> Unit = {}) {
        if (!begin(onDone)) return
        send(ListOp.Remove(id), TOAST_IMAGE_DELETED, onDone)
    }
}
