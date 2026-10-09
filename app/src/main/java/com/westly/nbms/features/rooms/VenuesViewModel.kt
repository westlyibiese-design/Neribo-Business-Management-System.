package com.westly.nbms.features.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.archive.RecordArchiver
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.feature.ImageFieldProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- Pure rules (unit-tested) ---------------------------------------------------------------

internal const val MSG_VENUE_NAME_TITLE = "Name required"
internal const val MSG_VENUE_NAME_BODY = "Please give the venue a name."
internal const val MSG_VENUE_CAPACITY_INVALID = "Guest capacity must be a whole number of 1 or more."
internal const val MSG_VENUE_PRICE_INVALID = "Price must be 0 or more."

/** What the person typed in the Add/Edit Venue form. Numbers are still text here. */
data class VenueFormInput(
    val name: String = "",
    val description: String = "",
    val size: String = "",
    val capacityText: String = "",
    val priceText: String = "",
    val amenitiesText: String = "",
    val images: List<String> = emptyList(),
    val available: Boolean = true
)

/** One message per field, null when the field is fine. A missing name is reported with a toast, not here. */
data class VenueFormErrors(
    val capacity: String? = null,
    val price: String? = null
) {
    val hasErrors: Boolean get() = capacity != null || price != null
}

/** The whole number typed, or null when the text is not one. */
internal fun parseVenueCapacity(text: String): Int? = text.trim().toIntOrNull()

internal fun validateVenueForm(input: VenueFormInput): VenueFormErrors {
    val capacityText = input.capacityText.trim()
    val capacityError = if (capacityText.isNotEmpty() && (parseVenueCapacity(capacityText)?.let { it >= 1 } != true)) {
        MSG_VENUE_CAPACITY_INVALID
    } else null

    val priceText = input.priceText.trim()
    val price = priceText.toDoubleOrNull()
    val priceError = if (priceText.isNotEmpty() && (price == null || price < 0.0 || price.isNaN() || price.isInfinite())) {
        MSG_VENUE_PRICE_INVALID
    } else null
    return VenueFormErrors(capacityError, priceError)
}

/** True when the name box holds a usable name. */
internal fun hasVenueName(input: VenueFormInput): Boolean = input.name.isNotBlank()

/**
 * The fields saved for a venue: name, description, size, capacity (int or null), price (double or null),
 * amenities, available, images. Call only after [validateVenueForm] found no errors.
 */
internal fun venueFields(input: VenueFormInput): Map<String, Any?> = mapOf(
    "name" to input.name.trim(),
    "description" to input.description.trim(),
    "size" to input.size.trim(),
    "capacity" to input.capacityText.trim().toIntOrNull(),
    "price" to input.priceText.trim().toDoubleOrNull(),
    "amenities" to splitAmenities(input.amenitiesText),
    "available" to input.available,
    "images" to input.images.map { it.trim() }.filter { it.isNotEmpty() }
)

/** The document written when a venue is created. */
internal fun newVenueDocument(fields: Map<String, Any?>, createdAt: Any): Map<String, Any?> =
    fields + mapOf("isDeleted" to false, "createdAt" to createdAt)

/** Venues A to Z. */
internal fun sortVenues(venues: List<Venue>): List<Venue> = venues.sortedBy { it.name.lowercase() }

/** Venues for a filter chip: "all", "available" or "unavailable". */
internal fun filterVenues(venues: List<Venue>, filterKey: String): List<Venue> = when (filterKey) {
    "available" -> venues.filter { it.available }
    "unavailable" -> venues.filter { !it.available }
    else -> venues
}

/** The audit action for the availability switch. */
internal fun availabilityAction(old: Boolean, new: Boolean): String =
    "venue_availability:${availabilityWord(old)}→${availabilityWord(new)}"

private fun availabilityWord(available: Boolean) = if (available) "available" else "unavailable"

/** The old values of a venue that an edit may change, for the audit trail. */
internal fun venueSnapshot(venue: Venue): Map<String, Any?> = mapOf(
    "name" to venue.name,
    "description" to venue.description,
    "size" to venue.size,
    "capacity" to venue.capacity,
    "price" to venue.price,
    "amenities" to venue.amenities,
    "available" to venue.available,
    "images" to venue.images
)

// ---- ViewModel ------------------------------------------------------------------------------

data class VenuesUiState(
    val saving: Boolean = false,
    /** Ids of venues whose switch or delete is running. */
    val busyIds: Set<String> = emptySet()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VenuesViewModel @Inject constructor(
    private val firestore: BusinessFirestore,
    private val audit: AuditLogger,
    private val archiver: RecordArchiver,
    private val toast: ToastController,
    imageProviders: Set<ImageFieldProvider>
) : ViewModel() {

    private val _state = MutableStateFlow(VenuesUiState())
    val state: StateFlow<VenuesUiState> = _state.asStateFlow()

    val imageProvider: ImageFieldProvider? = imageProviders.firstOrNull()

    private val retryTick = MutableStateFlow(0)

    /** Live venues of this business, without deleted ones, A to Z. */
    val venues: StateFlow<Resource<List<Venue>>> = retryTick
        .flatMapLatest { firestore.observeList("venues", Venue::class.java) }
        .map { r -> if (r is Resource.Success) Resource.Success(sortVenues(r.data.filter { !it.isDeleted })) else r }
        .catch { emit(Resource.Error("We couldn't load venues.", it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Adds a venue, or saves changes to [editing]. [onSuccess] runs on success so the form can close. */
    fun saveVenue(editing: Venue?, input: VenueFormInput, onSuccess: () -> Unit) {
        if (_state.value.saving) return
        if (!hasVenueName(input)) {
            toast.show(MSG_VENUE_NAME_BODY, ToastType.Error, MSG_VENUE_NAME_TITLE)
            return
        }
        if (validateVenueForm(input).hasErrors) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val fields = venueFields(input)
                if (editing == null) {
                    val id = firestore.add("venues", newVenueDocument(fields, FieldValue.serverTimestamp()))
                    audit.log("venue_created", "venues", id, null, fields)
                    toast.show("${fields["name"]} is now in your venues list.", ToastType.Success, "Venue Added")
                } else {
                    firestore.update("venues", editing.id, fields)
                    audit.log("venue_updated", "venues", editing.id, venueSnapshot(editing), fields)
                    toast.show("${fields["name"]} was saved.", ToastType.Success, "Venue Updated")
                }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_GENERIC, ToastType.Error, "Error")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** The "Available for booking" switch on a venue card. */
    fun setAvailable(venue: Venue, available: Boolean) {
        if (venue.id in _state.value.busyIds || venue.available == available) return
        markBusy(venue.id, true)
        viewModelScope.launch {
            try {
                firestore.update("venues", venue.id, mapOf("available" to available))
                audit.log(
                    availabilityAction(venue.available, available), "venues", venue.id,
                    mapOf("available" to venue.available), mapOf("available" to available)
                )
                toast.show(venue.name, ToastType.Success, if (available) "Venue Marked Available" else "Venue Marked Unavailable")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_GENERIC, ToastType.Error, "Error")
            } finally {
                markBusy(venue.id, false)
            }
        }
    }

    /** Soft-deletes the venue; it can be restored from Deleted Records. */
    fun deleteVenue(venue: Venue) {
        if (venue.id in _state.value.busyIds) return
        markBusy(venue.id, true)
        viewModelScope.launch {
            try {
                val failure = archiver.softDelete("venues", venue.id, "Venue ${venue.name}", "Deleted from admin panel").exceptionOrNull()
                if (failure == null) {
                    toast.show("${venue.name} was moved to Deleted Records.", ToastType.Success, "Venue Deleted")
                } else {
                    toast.show(failure.message ?: MSG_GENERIC, ToastType.Error, "Delete Failed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(e.message ?: MSG_GENERIC, ToastType.Error, "Delete Failed")
            } finally {
                markBusy(venue.id, false)
            }
        }
    }

    private fun markBusy(id: String, busy: Boolean) {
        _state.update { it.copy(busyIds = if (busy) it.busyIds + id else it.busyIds - id) }
    }
}
