package com.westly.nbms.features.gym

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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal const val TITLE_CANT_SAVE_YET = "Can't save yet"
internal const val MSG_CONTENT_LOAD_FAILED_SAVE =
    "Gym content failed to load, so saving now could overwrite it with incomplete data. Reload the page first."
internal const val TITLE_CONTENT_ERROR = "Error"
internal const val TITLE_CONTENT_NOT_SAVED = "Not saved"
internal const val MSG_CONTENT_STILL_LOADING = "Gym content is still loading. Try again in a moment."
internal const val MSG_CONTENT_LIMIT_EQUIPMENT = "You can add up to 20 equipment and service items."
internal const val MSG_CONTENT_LIMIT_PACKAGES = "You can add up to 12 membership packages."
internal const val MSG_CONTENT_LIMIT_PROGRAMS = "You can add up to 20 programs."
internal const val MSG_CONTENT_LIMIT_GALLERY = "The gallery can hold up to 24 images."
internal const val MSG_CONTENT_NAME_DESCRIPTION = "Name and description are required."
internal const val MSG_CONTENT_NAME = "Package name is required."
internal const val MSG_CONTENT_HOURS = "Operating hours need exactly seven days."

data class GymContentUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val content: GymContent = GymContent(),
    val saving: Boolean = false
)

/**
 * The state and every change of the gym content (About, Equipment, Hours, Packages, Programs, Gallery). UI-free.
 * Each change saves immediately through [GymContentSource.saveSection] and shows its toast. A refused call (still loading,
 * load failed, already saving, bad input, limit reached, unknown id) writes nothing and calls `onDone(false)`.
 */
@HiltViewModel
class GymContentViewModel internal constructor(
    private val source: GymContentSource,
    private val toast: ToastController,
    val imageProviders: Set<ImageFieldProvider>
) : ViewModel() {

    @Inject
    constructor(
        repository: GymContentRepository,
        toast: ToastController,
        imageProviders: @JvmSuppressWildcards Set<ImageFieldProvider>
    ) : this(repository as GymContentSource, toast, imageProviders)

    private val _state = MutableStateFlow(GymContentUiState())
    val state: StateFlow<GymContentUiState> = _state.asStateFlow()

    private val busy = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            source.observe()
                .catch { _state.update { s -> s.copy(loading = false, loadFailed = true) } }
                .collect { resource ->
                    when (resource) {
                        is Resource.Loading -> Unit
                        is Resource.Error -> _state.update { it.copy(loading = false, loadFailed = true) }
                        is Resource.Success -> _state.update { it.copy(loading = false, loadFailed = false, content = resource.data) }
                    }
                }
        }
    }

    // ── saving ──

    private fun refuse(title: String, message: String, onDone: (Boolean) -> Unit) {
        toast.show(message = message, type = ToastType.Error, title = title)
        onDone(false)
    }

    /**
     * The checks every change goes through, in order: load-error guard, still loading, already saving. Returns true when the
     * caller may go on (and has taken the saving flag).
     */
    private fun begin(onDone: (Boolean) -> Unit): Boolean {
        val s = _state.value
        if (s.loadFailed) {
            refuse(TITLE_CANT_SAVE_YET, MSG_CONTENT_LOAD_FAILED_SAVE, onDone)
            return false
        }
        if (s.loading) {
            refuse(TITLE_CONTENT_NOT_SAVED, MSG_CONTENT_STILL_LOADING, onDone)
            return false
        }
        if (!busy.compareAndSet(false, true)) {
            onDone(false)
            return false
        }
        _state.update { it.copy(saving = true) }
        return true
    }

    private fun commit(section: GymSection, value: Any, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            var ok = false
            try {
                source.saveSection(section, value)
                toast.show(message = section.successToast, type = ToastType.Success)
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: "Something went wrong. Please try again.", type = ToastType.Error, title = TITLE_CONTENT_ERROR)
            } finally {
                _state.update { it.copy(saving = false) }
                busy.set(false)
            }
            onDone(ok)
        }
    }

    /** Runs a change: [edit] works out the new content from the current one (null = refuse silently), then it is saved. */
    private fun change(section: GymSection, onDone: (Boolean) -> Unit, edit: (GymContent) -> GymContent?) {
        if (!begin(onDone)) return
        val next = try {
            edit(_state.value.content)
        } catch (e: Exception) {
            null
        }
        if (next == null) {
            _state.update { it.copy(saving = false) }
            busy.set(false)
            onDone(false)
            return
        }
        commit(section, gymSectionValue(section, next), onDone)
    }

    /** Used inside a change: shows the "Not saved" toast and refuses the change (null). */
    private fun reject(message: String): GymContent? {
        toast.show(message = message, type = ToastType.Error, title = TITLE_CONTENT_NOT_SAVED)
        return null
    }

    // ── About / Hours ──

    fun saveAbout(text: String, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.ABOUT, onDone) { it.copy(about = text.trim()) }
    }

    fun saveHours(rows: List<HoursRow>, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.HOURS, onDone) { if (rows.size != 7) reject(MSG_CONTENT_HOURS) else it.copy(hours = rows) }
    }

    // ── Equipment ──

    fun saveEquipmentItem(item: EquipmentItem, onDone: (Boolean) -> Unit = {}) {
        val clean = item.copy(
            id = item.id.trim().ifEmpty { GymContentRules.newItemId() },
            name = item.name.trim(),
            image = item.image.trim(),
            description = item.description.trim(),
            icon = if (item.icon in GymIcons.ALL) item.icon else GymIcons.DEFAULT
        )
        change(GymSection.EQUIPMENT, onDone) { content ->
            val isNew = content.equipment.none { it.id == clean.id }
            if (!GymContentRules.isValidEquipment(clean.name, clean.description)) {
                reject(MSG_CONTENT_NAME_DESCRIPTION)
            } else if (isNew && !GymContentRules.canAddEquipment(content.equipment.size)) {
                reject(MSG_CONTENT_LIMIT_EQUIPMENT)
            } else {
                content.copy(equipment = GymContentRules.upsertById(content.equipment, clean) { it.id })
            }
        }
    }

    fun deleteEquipmentItem(id: String, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.EQUIPMENT, onDone) { content ->
            val at = content.equipment.indexOfFirst { it.id == id }
            if (at < 0) null else content.copy(equipment = GymContentRules.removeAt(content.equipment, at))
        }
    }

    fun moveEquipmentItem(id: String, direction: Int, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.EQUIPMENT, onDone) { content ->
            val at = content.equipment.indexOfFirst { it.id == id }
            val moved = GymContentRules.moveItem(content.equipment, at, direction)
            if (at < 0 || moved == content.equipment) null else content.copy(equipment = moved)
        }
    }

    // ── Packages ──

    fun savePackage(item: PackageItem, onDone: (Boolean) -> Unit = {}) {
        val price = if (item.price.isNaN() || item.price.isInfinite() || item.price < 0.0) 0.0 else item.price
        val clean = item.copy(
            id = item.id.trim().ifEmpty { GymContentRules.newItemId() },
            name = item.name.trim(),
            price = price,
            duration = item.duration.trim().ifEmpty { "Monthly" },
            features = item.features.map { it.trim() }.filter { it.isNotEmpty() }
        )
        change(GymSection.PACKAGES, onDone) { content ->
            val isNew = content.packages.none { it.id == clean.id }
            if (!GymContentRules.isValidPackage(clean.name)) {
                reject(MSG_CONTENT_NAME)
            } else if (isNew && !GymContentRules.canAddPackage(content.packages.size)) {
                reject(MSG_CONTENT_LIMIT_PACKAGES)
            } else {
                content.copy(packages = GymContentRules.upsertById(content.packages, clean) { it.id })
            }
        }
    }

    fun deletePackage(id: String, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.PACKAGES, onDone) { content ->
            val at = content.packages.indexOfFirst { it.id == id }
            if (at < 0) null else content.copy(packages = GymContentRules.removeAt(content.packages, at))
        }
    }

    fun movePackage(id: String, direction: Int, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.PACKAGES, onDone) { content ->
            val at = content.packages.indexOfFirst { it.id == id }
            val moved = GymContentRules.moveItem(content.packages, at, direction)
            if (at < 0 || moved == content.packages) null else content.copy(packages = moved)
        }
    }

    // ── Programs ──

    fun saveProgram(item: ProgramItem, onDone: (Boolean) -> Unit = {}) {
        val clean = item.copy(
            id = item.id.trim().ifEmpty { GymContentRules.newItemId() },
            name = item.name.trim(),
            description = item.description.trim(),
            image = item.image.trim()
        )
        change(GymSection.PROGRAMS, onDone) { content ->
            val isNew = content.programs.none { it.id == clean.id }
            if (!GymContentRules.isValidProgram(clean.name, clean.description)) {
                reject(MSG_CONTENT_NAME_DESCRIPTION)
            } else if (isNew && !GymContentRules.canAddProgram(content.programs.size)) {
                reject(MSG_CONTENT_LIMIT_PROGRAMS)
            } else {
                content.copy(programs = GymContentRules.upsertById(content.programs, clean) { it.id })
            }
        }
    }

    fun deleteProgram(id: String, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.PROGRAMS, onDone) { content ->
            val at = content.programs.indexOfFirst { it.id == id }
            if (at < 0) null else content.copy(programs = GymContentRules.removeAt(content.programs, at))
        }
    }

    fun moveProgram(id: String, direction: Int, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.PROGRAMS, onDone) { content ->
            val at = content.programs.indexOfFirst { it.id == id }
            val moved = GymContentRules.moveItem(content.programs, at, direction)
            if (at < 0 || moved == content.programs) null else content.copy(programs = moved)
        }
    }

    // ── Gallery ──

    fun addGalleryImage(url: String, onDone: (Boolean) -> Unit = {}) {
        val clean = url.trim()
        if (clean.isEmpty()) {
            onDone(false)
            return
        }
        change(GymSection.GALLERY, onDone) { content ->
            if (!GymContentRules.canAddGalleryImage(content.gallery.size)) {
                reject(MSG_CONTENT_LIMIT_GALLERY)
            } else {
                content.copy(gallery = content.gallery + clean)
            }
        }
    }

    fun removeGalleryImage(index: Int, onDone: (Boolean) -> Unit = {}) {
        change(GymSection.GALLERY, onDone) { content ->
            if (index !in content.gallery.indices) null else content.copy(gallery = GymContentRules.removeAt(content.gallery, index))
        }
    }
}
