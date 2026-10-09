package com.westly.nbms.features.bookings

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

enum class BookingStatus(val key: String) {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    CHECKED_IN("checked_in"),
    CHECKED_OUT("checked_out"),
    CANCELLED("cancelled"),
    REJECTED("rejected"),
    NO_SHOW("no_show");

    companion object {
        /** null for an unknown key. */
        fun fromKey(key: String?): BookingStatus? = entries.firstOrNull { it.key == key }
    }
}

/** Firestore `businesses/{bid}/guests/{id}`. */
data class Guest(
    @DocumentId val id: String = "",
    val name: String = "",
    val email: String? = null,
    val phone: String? = null,
    val nationality: String? = null,
    val idDocumentRef: String? = null,
    val firstVisit: Timestamp? = null,
    val totalStays: Int = 1,
    // The annotation keeps the stored field name "isDeleted" (Firestore would otherwise read it as "deleted").
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** Firestore `businesses/{bid}/bookings/{id}` (Westly field names). */
data class Booking(
    @DocumentId val id: String = "",
    /** Human code such as "WI-AB12CD". */
    val bookingId: String? = null,
    val guestId: String? = null,
    val guestName: String = "",
    val guestEmail: String? = null,
    val guestPhone: String? = null,
    val roomId: String = "",
    val roomNumber: String = "",
    val roomType: String? = null,
    val checkIn: Timestamp? = null,
    val checkOut: Timestamp? = null,
    val checkInAt: Timestamp? = null,
    val checkOutAt: Timestamp? = null,
    val nights: Int? = null,
    val adults: Int = 1,
    val children: Int = 0,
    val pricePerNight: Double? = null,
    val totalAmount: Double = 0.0,
    val paymentMethod: String? = null,
    val paymentOption: String? = null,
    /** "paid" | "pending" */
    val roomPaymentStatus: String? = null,
    val status: String = "pending",
    /** "website" | "walk_in" | "admin" */
    val source: String? = null,
    val specialRequests: String? = null,
    val notes: String? = null,
    val createdAt: Timestamp? = null,
    val createdBy: String? = null,
    val createdByName: String? = null,
    val updatedAt: Timestamp? = null,
    val updatedBy: String? = null,
    val updatedByName: String? = null,
    val lastOverdueNotifiedAt: Timestamp? = null,
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)
