package com.westly.nbms.features.rooms

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

/** Firestore `businesses/{bid}/venues/{id}` (halls and event spaces). */
data class Venue(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String = "",
    /** Free text such as "450 sqm". */
    val size: String = "",
    val capacity: Int? = null,
    /** null means "Contact for pricing". */
    val price: Double? = null,
    val amenities: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    /** A venue without this field counts as available (Westly default). */
    val available: Boolean = true,
    // Same naming fix as Room.isDeleted: keep the stored field name "isDeleted".
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false,
    val createdAt: Timestamp? = null
)
