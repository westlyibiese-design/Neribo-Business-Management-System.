package com.westly.nbms.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.archive.RecordArchiver
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.settings.models.DeletedRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

private val COLLECTION_LABELS = mapOf(
    "rooms" to "Room",
    "venues" to "Venue",
    "bookings" to "Booking",
    "guests" to "Guest",
    "users" to "Staff Account",
    "sales" to "Sale",
    "expenses" to "Expense",
    "inventory" to "Inventory Item",
    "attendance" to "Attendance Record",
    "maintenance" to "Maintenance Request",
    "housekeeping_tasks" to "Housekeeping Task"
)

/** "rooms" -> "Room"; anything else is the collection name with underscores turned into spaces. */
internal fun collectionLabel(collection: String): String =
    COLLECTION_LABELS[collection] ?: collection.replace('_', ' ')

enum class CollectionTone { BLUE, PURPLE, GREEN, RED, YELLOW, ORANGE, TEAL, GRAY }

internal fun collectionTone(collection: String): CollectionTone = when (collection) {
    "rooms" -> CollectionTone.BLUE
    "bookings" -> CollectionTone.PURPLE
    "guests" -> CollectionTone.GREEN
    "users" -> CollectionTone.RED
    "sales" -> CollectionTone.YELLOW
    "expenses" -> CollectionTone.ORANGE
    "inventory" -> CollectionTone.TEAL
    else -> CollectionTone.GRAY
}

/** The record's own label, or "{Label} · {first 8 characters of the id}" when it has none. */
internal fun displayLabel(r: DeletedRecord): String =
    r.label.trim().ifEmpty { "${collectionLabel(r.originalCollection)} · ${r.originalDocumentId.take(8)}" }

/** Newest deletion first; records the server has not stamped yet go last. */
internal fun sortDeleted(
    records: List<DeletedRecord>,
    millis: (DeletedRecord) -> Long? = { r -> r.deletedAt?.let { it.seconds * 1000L + it.nanoseconds / 1_000_000 } }
): List<DeletedRecord> = records.sortedByDescending { millis(it) ?: Long.MIN_VALUE }

/** Every distinct collection, A to Z by its display label (feeds the filter chips). */
internal fun distinctDeletedCollections(records: List<DeletedRecord>): List<String> =
    records.map { it.originalCollection }.filter { it.isNotBlank() }.distinct().sortedBy { collectionLabel(it).lowercase() }

/** [collection] = null means "All Collections". A collection that no longer has records also shows everything. */
internal fun filterDeleted(records: List<DeletedRecord>, collection: String?): List<DeletedRecord> {
    if (collection == null || records.none { it.originalCollection == collection }) return records
    return records.filter { it.originalCollection == collection }
}

/** "Deleted by Ada (Receptionist) · 12 Mar 2025, 14:30" without the date part. */
internal fun deletedByText(r: DeletedRecord): String {
    val role = Role.fromKey(r.deletedByRole)?.label ?: r.deletedByRole.replace('_', ' ')
    val name = r.deletedByName.ifBlank { "Unknown" }
    return if (role.isBlank()) "Deleted by $name" else "Deleted by $name ($role)"
}

// ---- ViewModel ------------------------------------------------------------------------------

enum class ArchiveAction { RESTORE, PURGE }

data class PendingArchiveAction(val record: DeletedRecord, val action: ArchiveAction)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DeletedRecordsViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val archiver: RecordArchiver,
    private val toast: ToastController
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    val records: StateFlow<Resource<List<DeletedRecord>>> = retryTick
        .flatMapLatest { firestore.observeList("deleted_records", DeletedRecord::class.java) }
        .map { r -> if (r is Resource.Success) Resource.Success(sortDeleted(r.data)) else r }
        .catch { emit(Resource.Error("We couldn't load deleted records.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    private val _collection = MutableStateFlow<String?>(null)
    /** null = "All Collections". */
    val collection: StateFlow<String?> = _collection.asStateFlow()

    val filtered: StateFlow<List<DeletedRecord>> = combine(records, _collection) { r, c ->
        if (r is Resource.Success) filterDeleted(r.data, c) else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<String>> = records
        .map { r -> if (r is Resource.Success) distinctDeletedCollections(r.data) else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pending = MutableStateFlow<PendingArchiveAction?>(null)
    val pending: StateFlow<PendingArchiveAction?> = _pending.asStateFlow()

    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()

    fun retry() {
        retryTick.update { it + 1 }
    }

    fun onCollection(value: String?) {
        _collection.value = value
    }

    fun ask(record: DeletedRecord, action: ArchiveAction) {
        if (_processing.value) return
        _pending.value = PendingArchiveAction(record, action)
    }

    fun cancel() {
        if (!_processing.value) _pending.value = null
    }

    fun confirm() {
        val p = _pending.value ?: return
        if (_processing.value) return
        _processing.value = true
        viewModelScope.launch {
            val label = collectionLabel(p.record.originalCollection)
            val result = when (p.action) {
                ArchiveAction.RESTORE -> archiver.restore(p.record.id)
                ArchiveAction.PURGE -> archiver.purge(p.record.id)
            }
            _processing.value = false
            val failure = result.exceptionOrNull()
            if (failure == null) {
                _pending.value = null
                when (p.action) {
                    ArchiveAction.RESTORE -> toast.show("$label has been restored successfully.", ToastType.Success, "Record Restored")
                    ArchiveAction.PURGE -> toast.show("$label has been permanently purged.", ToastType.Success, "Record Permanently Deleted")
                }
            } else {
                _pending.value = null
                val title = if (p.action == ArchiveAction.RESTORE) "Restore Failed" else "Purge Failed"
                toast.show(failure.message ?: "Something went wrong. Please try again.", ToastType.Error, title)
            }
        }
    }
}
