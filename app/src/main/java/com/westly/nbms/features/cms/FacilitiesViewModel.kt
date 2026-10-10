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

internal const val DOC_FACILITIES = "facilities"
internal const val AUDIT_FACILITIES_UPDATED = "facilities_updated"
internal const val FACILITIES_IMAGE_FOLDER = "facilities"

internal const val TITLE_CANT_SAVE_YET = "Can't save yet"
internal const val TITLE_NOT_SAVED = "Not saved"
internal const val TITLE_ERROR = "Error"
internal const val MSG_FACILITIES_LOAD_FAILED_SAVE =
    "Facilities failed to load, so saving now could overwrite them with incomplete data. Reload the page first."
internal const val MSG_FACILITIES_STILL_LOADING = "Facilities are still loading. Try again in a moment."
internal const val MSG_FACILITY_NAME_DESCRIPTION = "Name and description are required."
internal const val MSG_FACILITIES_LIMIT = "You've reached the maximum of 20 facilities. Delete one to add another."
internal const val MSG_ALREADY_CHANGED = "That item was already changed by someone else."
internal const val TOAST_FACILITY_ADDED = "Facility Added"
internal const val TOAST_FACILITY_UPDATED = "Facility Updated"
internal const val TOAST_FACILITY_DELETED = "Facility Deleted"

/** The way a facility is read, written and identified inside `cms_content/facilities`. */
internal val FACILITY_CODEC: ListCodec<FacilityItem> = ListCodec(
    parse = { FacilityItem.parseList(it) },
    toMap = { it.toMap() },
    idOf = { it.id }
)

/** The plain rules of the Facilities page (UI-free so they can be tested alone). */
object FacilitiesRules {
    const val MAX = CmsLimits.FACILITIES

    fun isValid(name: String, description: String): Boolean = name.isNotBlank() && description.isNotBlank()

    fun canAdd(count: Int): Boolean = count < MAX

    fun heading(count: Int): String = "Facilities ($count/$MAX)"

    fun deleteBody(name: String): String = "Are you sure you want to delete \"$name\"? This will remove it from the public website immediately."
}

data class FacilitiesUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val facilities: List<FacilityItem> = emptyList(),
    val saving: Boolean = false
)

/**
 * The state and every change of the Facilities list. UI-free. Each change is one list operation sent to
 * [CmsSource.mutateList], so another person's edit of a different facility is never lost. A refused call (still loading,
 * load failed, already saving, bad input, limit reached) writes nothing and calls `onDone(false)`.
 */
@HiltViewModel
class FacilitiesViewModel internal constructor(
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

    private val _state = MutableStateFlow(FacilitiesUiState())
    val state: StateFlow<FacilitiesUiState> = _state.asStateFlow()

    private val guard = CmsSaveGuard()

    init {
        viewModelScope.launch {
            source.observeDoc(DOC_FACILITIES)
                .catch { _state.update { s -> s.copy(loading = false, loadFailed = true) } }
                .collect { resource ->
                    when (resource) {
                        is Resource.Loading -> Unit
                        is Resource.Error -> _state.update { it.copy(loading = false, loadFailed = true) }
                        is Resource.Success -> _state.update {
                            it.copy(loading = false, loadFailed = false, facilities = FacilityItem.parseList(resource.data.data))
                        }
                    }
                }
        }
    }

    // ── the checks every change goes through ──

    private fun refuse(title: String, message: String, onDone: (Boolean) -> Unit) {
        toast.show(message = message, type = ToastType.Error, title = title)
        onDone(false)
    }

    /** Load-error guard, still loading, already saving — in that order. True means the caller has taken the saving flag. */
    private fun begin(onDone: (Boolean) -> Unit): Boolean {
        val s = _state.value
        if (s.loadFailed) {
            refuse(TITLE_CANT_SAVE_YET, MSG_FACILITIES_LOAD_FAILED_SAVE, onDone)
            return false
        }
        if (s.loading) {
            refuse(TITLE_NOT_SAVED, MSG_FACILITIES_STILL_LOADING, onDone)
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

    /** Sends one operation. [successToast] is null for a silent change (reordering). */
    private fun send(op: ListOp<FacilityItem>, successToast: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            var ok = false
            try {
                when (source.mutateList(DOC_FACILITIES, op, FACILITY_CODEC, CmsLimits.FACILITIES, AUDIT_FACILITIES_UPDATED)) {
                    MutateResult.Done -> {
                        if (successToast != null) toast.show(message = successToast, type = ToastType.Success)
                        ok = true
                    }
                    MutateResult.AlreadyChanged -> toast.show(message = MSG_ALREADY_CHANGED, type = ToastType.Error)
                    MutateResult.LimitReached -> toast.show(message = MSG_FACILITIES_LIMIT, type = ToastType.Error, title = TITLE_NOT_SAVED)
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

    // ── the changes ──

    /** Adds a facility ([id] null or blank) or replaces the one with that [id]. Name and description are required. */
    fun saveFacility(id: String?, name: String, image: String, description: String, onDone: (Boolean) -> Unit = {}) {
        if (!begin(onDone)) return
        val cleanName = name.trim()
        val cleanDescription = description.trim()
        if (!FacilitiesRules.isValid(cleanName, cleanDescription)) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_FACILITY_NAME_DESCRIPTION, onDone)
            return
        }
        val isNew = id.isNullOrBlank()
        if (isNew && !FacilitiesRules.canAdd(_state.value.facilities.size)) {
            release()
            refuse(TITLE_NOT_SAVED, MSG_FACILITIES_LIMIT, onDone)
            return
        }
        val item = FacilityItem(
            id = if (isNew) CmsIds.newId() else id!!.trim(),
            name = cleanName,
            image = image.trim(),
            description = cleanDescription
        )
        if (isNew) send(ListOp.Add(item), TOAST_FACILITY_ADDED, onDone)
        else send(ListOp.Replace(item), TOAST_FACILITY_UPDATED, onDone)
    }

    fun deleteFacility(id: String, onDone: (Boolean) -> Unit = {}) {
        if (!begin(onDone)) return
        send(ListOp.Remove(id), TOAST_FACILITY_DELETED, onDone)
    }

    /** Moves a facility one place up ([delta] = -1) or down (+1). Silent: no success toast. */
    fun moveFacility(id: String, delta: Int, onDone: (Boolean) -> Unit = {}) {
        if (!begin(onDone)) return
        send(ListOp.Move(id, delta), null, onDone)
    }
}
