package com.westly.nbms.features.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.features.users.models.AuditLogEntry
import com.westly.nbms.features.users.models.timeMillis
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

internal const val AUDIT_PAGE_LIMIT = 200

/** "1 entry", "0 entries", "5 entries". */
internal fun entryCountText(count: Int): String = if (count == 1) "1 entry" else "$count entries"

/** Newest first. Entries the server has not stamped yet go last. */
internal fun sortNewestFirst(
    entries: List<AuditLogEntry>,
    millis: (AuditLogEntry) -> Long? = { it.timeMillis() }
): List<AuditLogEntry> = entries.sortedByDescending { millis(it) ?: Long.MIN_VALUE }

/**
 * The search box matches `action` and `userName` ignoring case, and `documentId` by "contains".
 * [collection] = null means "All Collections".
 */
internal fun filterAuditEntries(
    entries: List<AuditLogEntry>,
    search: String,
    collection: String?
): List<AuditLogEntry> {
    val term = search.trim()
    val lower = term.lowercase()
    return entries.filter { e ->
        val matchesCollection = collection == null || e.collection == collection
        val matchesSearch = term.isEmpty() ||
            e.action.lowercase().contains(lower) ||
            e.userName.lowercase().contains(lower) ||
            e.documentId.contains(term)
        matchesCollection && matchesSearch
    }
}

/** Every distinct collection name, A to Z (feeds the "All Collections" dropdown). */
internal fun distinctCollections(entries: List<AuditLogEntry>): List<String> =
    entries.map { it.collection }.filter { it.isNotBlank() }.distinct().sorted()

/** First 12 characters of a document id, with an ellipsis when it was cut. */
internal fun shortDocId(id: String): String = if (id.length > 12) id.take(12) + "…" else id

/** The part of an action before the first ":" ("check_in:101" -> "check_in"). */
internal fun actionPrefix(action: String): String = action.substringBefore(':')

enum class AuditTone { GREEN, BLUE, TEAL, GRAY, PURPLE, RED, YELLOW, ORANGE, MUTED }

/** Colour family for an action pill; anything not listed is muted. */
internal fun toneForAction(action: String): AuditTone = when (actionPrefix(action)) {
    "check_in" -> AuditTone.GREEN
    "check_out" -> AuditTone.BLUE
    "walk_in_checkin" -> AuditTone.TEAL
    "admin_login", "pin_login" -> AuditTone.GRAY
    "user_created" -> AuditTone.PURPLE
    "user_suspended" -> AuditTone.RED
    // The server logs "user_reactivated" when an account is restored; Westly called it "user_active".
    "user_active", "user_reactivated" -> AuditTone.GREEN
    "soft_delete" -> AuditTone.RED
    "restore" -> AuditTone.GREEN
    "room_created" -> AuditTone.BLUE
    "room_updated" -> AuditTone.YELLOW
    "new_sale" -> AuditTone.ORANGE
    else -> AuditTone.MUTED
}

// ---- ViewModel ------------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AuditLogViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val toast: ToastController
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    /** Live audit entries, newest first. */
    val entries: StateFlow<Resource<List<AuditLogEntry>>> = retryTick
        .flatMapLatest { firestore.observeList("audit_logs", AuditLogEntry::class.java) }
        .map { r -> if (r is Resource.Success) Resource.Success(sortNewestFirst(r.data)) else r }
        .catch { emit(Resource.Error("We couldn't load the audit log.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search

    /** null = "All Collections". */
    private val _collection = MutableStateFlow<String?>(null)
    val collection: StateFlow<String?> = _collection

    /** Entries that match the search and the collection filter (not yet cut to 200). */
    val filtered: StateFlow<List<AuditLogEntry>> = combine(entries, _search, _collection) { r, s, c ->
        if (r is Resource.Success) filterAuditEntries(r.data, s, c) else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<String>> = entries
        .map { r -> if (r is Resource.Success) distinctCollections(r.data) else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearch(value: String) {
        _search.value = value
    }

    fun onCollection(value: String?) {
        _collection.value = value
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** The CSV text for the filtered list and the file name to save it under. */
    fun csvForExport(): Pair<String, String> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        return buildAuditCsv(filtered.value) to auditCsvFileName(today)
    }

    fun exportFailed() {
        toast.show("The file could not be shared. Please try again.", ToastType.Error, "Export failed")
    }
}
