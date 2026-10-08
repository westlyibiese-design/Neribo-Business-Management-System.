package com.westly.nbms.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Validators
import com.westly.nbms.features.settings.models.HotelSettings
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
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

internal const val MSG_NAME_LENGTH = "Hotel name must be 2 to 120 characters."
internal const val MSG_EMAIL = "Enter a valid email address."
internal const val MSG_TIMEZONE = "Enter a valid timezone, for example Africa/Lagos."
internal const val MSG_CURRENCY = "Use a 3-letter currency code, for example NGN."
internal const val MSG_LEAD = "Enter a number from 0 to 480."
internal const val MSG_SETTINGS_GENERIC = "Something went wrong. Please try again."

/** What the person is editing on the Settings page. Numbers and times stay as text until saved. */
data class SettingsForm(
    val hotelName: String = "",
    val tagline: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val checkInTime: String = "14:00",
    val checkOutTime: String = "11:00",
    val currency: String = "NGN",
    val leadMinutes: String = "60",
    val timezone: String = "Africa/Lagos",
    val serviceTime: String = "10:00",
    val serviceEnabled: Boolean = true,
    val instagram: String = "",
    val facebook: String = "",
    val twitter: String = ""
)

data class SettingsErrors(
    val hotelName: String? = null,
    val email: String? = null,
    val currency: String? = null,
    val leadMinutes: String? = null,
    val timezone: String? = null
) {
    val any: Boolean get() = listOfNotNull(hotelName, email, currency, leadMinutes, timezone).isNotEmpty()
}

/** Turns the saved document into form text. A missing document uses the defaults and the business name. */
internal fun formFrom(s: HotelSettings, fallbackName: String): SettingsForm = SettingsForm(
    hotelName = s.hotelName.ifBlank { fallbackName },
    tagline = s.tagline,
    phone = s.phone,
    email = s.email,
    address = s.address,
    checkInTime = s.checkInTime.ifBlank { "14:00" },
    checkOutTime = s.checkOutTime.ifBlank { "11:00" },
    currency = s.currency.ifBlank { "NGN" },
    leadMinutes = s.housekeepingLeadTimeMinutes.toString(),
    timezone = s.timezone.ifBlank { "Africa/Lagos" },
    serviceTime = s.occupiedStayServiceTime.ifBlank { "10:00" },
    serviceEnabled = s.occupiedStayServiceEnabled,
    instagram = s.socialLinks.instagram,
    facebook = s.socialLinks.facebook,
    twitter = s.socialLinks.twitter
)

/** "14:30" -> 870 minutes after midnight; null when the text is not a valid time. */
internal fun parseHm(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 870 -> "14:30" (zero-padded, wraps around midnight). */
internal fun formatHm(minutesOfDay: Int): String {
    val wrapped = ((minutesOfDay % 1440) + 1440) % 1440
    return String.format(Locale.US, "%02d:%02d", wrapped / 60, wrapped % 60)
}

/** When the cleaning queue adds rooms: (check-out - lead) wrapped to 24 hours, e.g. 11:00 and 60 -> "10:00". */
internal fun cleaningQueueTime(checkOut: String, leadMinutes: Int): String? {
    val out = parseHm(checkOut) ?: return null
    return formatHm(out - leadMinutes)
}

/** The live helper line under "Cleaning Lead Time". */
internal fun leadTimeHelper(checkOut: String, leadMinutes: Int): String {
    val out = parseHm(checkOut)?.let { formatHm(it) } ?: checkOut
    val queue = cleaningQueueTime(checkOut, leadMinutes) ?: "--:--"
    return "E.g. with Check-Out Time $out and $leadMinutes minutes, tasks queue at $queue."
}

internal fun isValidTimezone(id: String): Boolean {
    if (id.isBlank()) return false
    return try {
        ZoneId.of(id.trim())
        true
    } catch (e: Exception) {
        false
    }
}

/** Currency is three letters, shown and saved in capitals. */
internal fun cleanCurrencyInput(raw: String): String = raw.filter { it.isLetter() }.take(3).uppercase(Locale.US)

internal fun cleanLeadInput(raw: String): String = raw.filter { it in '0'..'9' }.take(3)

internal fun validateSettingsForm(f: SettingsForm): SettingsErrors {
    val nameLength = f.hotelName.trim().length
    val email = f.email.trim()
    val lead = f.leadMinutes.trim().toIntOrNull()
    return SettingsErrors(
        hotelName = if (nameLength in 2..120) null else MSG_NAME_LENGTH,
        email = if (email.isEmpty() || Validators.email(email)) null else MSG_EMAIL,
        currency = if (f.currency.trim().length == 3 && f.currency.trim().all { it.isLetter() }) null else MSG_CURRENCY,
        leadMinutes = if (lead != null && lead in 0..480) null else MSG_LEAD,
        timezone = if (isValidTimezone(f.timezone)) null else MSG_TIMEZONE
    )
}

/** The map written to `settings/hotel` (merge). [serverTime] is FieldValue.serverTimestamp() in production. */
internal fun hotelSettingsMap(f: SettingsForm, serverTime: Any, uid: String): Map<String, Any?> = mapOf(
    "hotelName" to f.hotelName.trim(),
    "tagline" to f.tagline.trim(),
    "phone" to f.phone.trim(),
    "email" to f.email.trim(),
    "address" to f.address.trim(),
    "currency" to f.currency.trim().uppercase(Locale.US),
    "checkInTime" to f.checkInTime,
    "checkOutTime" to f.checkOutTime,
    "timezone" to f.timezone.trim(),
    "housekeepingLeadTimeMinutes" to maxOf(0, f.leadMinutes.trim().toIntOrNull() ?: 0),
    "occupiedStayServiceTime" to f.serviceTime,
    "occupiedStayServiceEnabled" to f.serviceEnabled,
    "socialLinks" to mapOf(
        "instagram" to f.instagram.trim(),
        "facebook" to f.facebook.trim(),
        "twitter" to f.twitter.trim()
    ),
    "updatedAt" to serverTime,
    "updatedBy" to uid
)

// ---- ViewModel ------------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val api: BusinessProfileApi,
    private val audit: AuditLogger,
    private val session: SessionManager,
    private val toast: ToastController
) : ViewModel() {

    private val retryTick = MutableStateFlow(0)

    /** Loading / error state of `settings/hotel`. A missing document is a success with null. */
    val load: StateFlow<Resource<HotelSettings?>> = retryTick
        .flatMapLatest { firestore.observeDoc("settings", "hotel", HotelSettings::class.java) }
        .catch { emit(Resource.Error("We couldn't load settings.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    private val _form = MutableStateFlow<SettingsForm?>(null)
    /** Null until the saved settings have loaded once; after that the person's edits are kept. */
    val form: StateFlow<SettingsForm?> = _form.asStateFlow()

    private val _errors = MutableStateFlow(SettingsErrors())
    val errors: StateFlow<SettingsErrors> = _errors.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        viewModelScope.launch {
            load.collect { r ->
                if (r is Resource.Success && _form.value == null) {
                    val fallback = (session.state.value as? SessionState.SignedIn)?.business?.name.orEmpty()
                    _form.value = formFrom(r.data ?: HotelSettings(), fallback)
                }
            }
        }
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    fun edit(change: (SettingsForm) -> SettingsForm) {
        _form.update { current -> current?.let(change) }
        if (_errors.value.any) _errors.value = _form.value?.let { validateSettingsForm(it) } ?: SettingsErrors()
    }

    fun codeCopied() {
        toast.show("Business code copied.", ToastType.Info)
    }

    fun save() {
        val f = _form.value ?: return
        if (_saving.value) return
        val found = validateSettingsForm(f)
        _errors.value = found
        if (found.any) return
        val signedIn = session.state.value as? SessionState.SignedIn
        if (signedIn == null) {
            toast.show("Not signed in", ToastType.Error, "Error")
            return
        }
        _saving.value = true
        viewModelScope.launch {
            try {
                firestore.set("settings", "hotel", hotelSettingsMap(f, FieldValue.serverTimestamp(), signedIn.user.uid), merge = true)
                val profile = api.update(
                    name = f.hotelName.trim(),
                    currency = f.currency.trim().uppercase(Locale.US),
                    timezone = f.timezone.trim()
                )
                val failure = profile.exceptionOrNull()
                audit.log("settings_updated", "settings", "hotel")
                if (failure != null) {
                    toast.show(
                        "Your settings were saved, but the business profile could not be updated. " +
                            (failure.message ?: MSG_SETTINGS_GENERIC),
                        ToastType.Error,
                        "Error"
                    )
                } else {
                    toast.show("Your changes have been saved.", ToastType.Success, "Settings Saved")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_SETTINGS_GENERIC, ToastType.Error, "Error")
            } finally {
                _saving.value = false
            }
        }
    }
}
