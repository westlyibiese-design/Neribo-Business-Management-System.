package com.westly.nbms.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.features.settings.models.DEFAULT_MAINTENANCE_TEXT
import com.westly.nbms.features.settings.models.DEFAULT_MAINTENANCE_TITLE
import com.westly.nbms.features.settings.models.MaintenanceSettings
import com.westly.nbms.features.settings.models.MaintenanceTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

internal const val MSG_RETURN_BOTH = "Pick both a date and a time, or clear both."
internal const val MSG_MAINT_GENERIC = "Something went wrong. Please try again."
internal const val MAINTENANCE_TITLE_MAX = 120

/** What the person is editing on the System Maintenance page. */
data class MaintenanceForm(
    val target: String = MaintenanceTarget.NONE,
    val title: String = "",
    val message: String = "",
    val returnDate: LocalDate? = null,
    val returnTime: LocalTime? = null
)

/** True when the staff app itself goes into maintenance ("admin" or "both"). */
internal fun maintenanceModeFor(target: String): Boolean =
    target == MaintenanceTarget.ADMIN || target == MaintenanceTarget.BOTH

internal fun targetLabel(target: String): String = when (target) {
    MaintenanceTarget.PUBLIC -> "Public Website Only"
    MaintenanceTarget.ADMIN -> "Admin System Only"
    MaintenanceTarget.BOTH -> "Both"
    else -> "Disabled"
}

internal fun targetDescription(target: String): String = when (target) {
    MaintenanceTarget.PUBLIC -> "Guests see the maintenance page. Admin/staff system is unaffected."
    MaintenanceTarget.ADMIN -> "Staff see the maintenance page. Public website is unaffected."
    MaintenanceTarget.BOTH -> "Public website and admin system both enter maintenance mode."
    else -> "Both systems operate normally."
}

/** A saved target the app does not know falls back to "none". */
internal fun safeTarget(target: String): String = if (target in MaintenanceTarget.all) target else MaintenanceTarget.NONE

internal fun zoneOrDefault(id: String?): TimeZone = try {
    if (id.isNullOrBlank()) TimeZone.currentSystemDefault() else TimeZone.of(id)
} catch (e: Exception) {
    TimeZone.currentSystemDefault()
}

/** Date + time in the business time zone -> ISO instant text ("2026-10-09T13:00:00Z"); null when either is missing. */
internal fun estimatedReturnIso(date: LocalDate?, time: LocalTime?, zone: TimeZone): String? {
    if (date == null || time == null) return null
    return LocalDateTime(date, time).toInstant(zone).toString()
}

/** The reverse of [estimatedReturnIso]; null when there is no valid saved value. */
internal fun splitEstimatedReturn(iso: String?, zone: TimeZone): Pair<LocalDate, LocalTime>? {
    if (iso.isNullOrBlank()) return null
    return try {
        val local = Instant.parse(iso).toLocalDateTime(zone)
        local.date to LocalTime(local.hour, local.minute)
    } catch (e: Exception) {
        null
    }
}

internal fun formFromSaved(saved: MaintenanceSettings, zone: TimeZone): MaintenanceForm {
    val split = splitEstimatedReturn(saved.estimatedReturn, zone)
    return MaintenanceForm(
        target = safeTarget(saved.target),
        title = saved.title,
        message = saved.message,
        returnDate = split?.first,
        returnTime = split?.second
    )
}

internal fun cleanTitle(form: MaintenanceForm): String = form.title.trim().ifEmpty { DEFAULT_MAINTENANCE_TITLE }
internal fun cleanMessage(form: MaintenanceForm): String = form.message.trim().ifEmpty { DEFAULT_MAINTENANCE_TEXT }

/** "Pick both..." when only one half of the return time is chosen; null when fine. */
internal fun validateReturn(form: MaintenanceForm): String? =
    if ((form.returnDate == null) != (form.returnTime == null)) MSG_RETURN_BOTH else null

/** Has the person changed anything compared with what is saved? Blank title / message count as the defaults. */
internal fun isDirty(form: MaintenanceForm, saved: MaintenanceForm): Boolean =
    form.target != saved.target ||
        cleanTitle(form) != cleanTitle(saved) ||
        cleanMessage(form) != cleanMessage(saved) ||
        form.returnDate != saved.returnDate ||
        form.returnTime != saved.returnTime

/** The map written to `settings/maintenance` (merge). */
internal fun maintenanceDocMap(
    form: MaintenanceForm,
    zone: TimeZone,
    serverTime: Any,
    uid: String,
    name: String
): Map<String, Any?> = mapOf(
    "target" to form.target,
    "title" to cleanTitle(form),
    "message" to cleanMessage(form),
    "estimatedReturn" to estimatedReturnIso(form.returnDate, form.returnTime, zone),
    "imageUrl" to null,
    "updatedAt" to serverTime,
    "updatedBy" to uid,
    "updatedByName" to name
)

/** The only two fields the rules let the Super Admin change on the business document. */
internal fun businessMaintenanceFields(form: MaintenanceForm): Map<String, Any?> = mapOf(
    "maintenanceMode" to maintenanceModeFor(form.target),
    "maintenanceMessage" to cleanMessage(form)
)

// ---- ViewModel ------------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SystemMaintenanceViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val db: FirebaseFirestore,
    private val audit: AuditLogger,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    /** Loading / error state of `settings/maintenance`. A missing document is a success with null. */
    val load: StateFlow<Resource<MaintenanceSettings?>> = retryTick
        .flatMapLatest { firestore.observeDoc("settings", "maintenance", MaintenanceSettings::class.java) }
        .catch { emit(Resource.Error("We couldn't load maintenance settings.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    private val _form = MutableStateFlow<MaintenanceForm?>(null)
    val form: StateFlow<MaintenanceForm?> = _form.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _returnError = MutableStateFlow<String?>(null)
    val returnError: StateFlow<String?> = _returnError.asStateFlow()

    private fun zone(): TimeZone =
        zoneOrDefault((session.state.value as? SessionState.SignedIn)?.business?.timezone)

    init {
        viewModelScope.launch {
            load.collect { r ->
                if (r is Resource.Success && _form.value == null) {
                    _form.value = formFromSaved(r.data ?: MaintenanceSettings(), zone())
                }
            }
        }
    }

    /** The saved settings as form values, used to know whether anything changed. */
    fun savedForm(saved: MaintenanceSettings?): MaintenanceForm = formFromSaved(saved ?: MaintenanceSettings(), zone())

    fun retry() {
        retryTick.update { it + 1 }
    }

    fun edit(change: (MaintenanceForm) -> MaintenanceForm) {
        _form.update { current -> current?.let(change) }
        _returnError.value = null
    }

    fun save() {
        val f = _form.value ?: return
        if (_saving.value) return
        val problem = validateReturn(f)
        _returnError.value = problem
        if (problem != null) return
        val signedIn = session.state.value as? SessionState.SignedIn
        if (signedIn == null) {
            toast.show("Not signed in", ToastType.Error, "Save Failed")
            return
        }
        val previousTarget = (load.value as? Resource.Success<MaintenanceSettings?>)?.data?.target ?: MaintenanceTarget.NONE
        _saving.value = true
        viewModelScope.launch {
            try {
                val batch = db.batch()
                batch.set(
                    firestore.doc("settings", "maintenance"),
                    maintenanceDocMap(f, zone(), FieldValue.serverTimestamp(), signedIn.user.uid, signedIn.user.name),
                    SetOptions.merge()
                )
                batch.update(
                    db.collection("businesses").document(signedIn.business.id),
                    businessMaintenanceFields(f)
                )
                batch.commit().await()
                audit.log(
                    "maintenance_mode_updated", "settings", "maintenance",
                    mapOf("target" to safeTarget(previousTarget)), mapOf("target" to f.target)
                )
                toast.show("Changes take effect immediately — no redeploy needed.", ToastType.Success, "Maintenance Settings Saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_MAINT_GENERIC, ToastType.Error, "Save Failed")
            } finally {
                _saving.value = false
            }
        }
    }
}
