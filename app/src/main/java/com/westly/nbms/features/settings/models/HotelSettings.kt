package com.westly.nbms.features.settings.models

import com.google.firebase.Timestamp

data class SocialLinks(
    val instagram: String = "",
    val facebook: String = "",
    val twitter: String = ""
)

/** `businesses/{bid}/settings/hotel`. Every field has a default so a missing document still loads. */
data class HotelSettings(
    val hotelName: String = "",
    val tagline: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val currency: String = "NGN",
    val checkInTime: String = "14:00",
    val checkOutTime: String = "11:00",
    val timezone: String = "Africa/Lagos",
    val housekeepingLeadTimeMinutes: Int = 60,
    val occupiedStayServiceTime: String = "10:00",
    val occupiedStayServiceEnabled: Boolean = true,
    val socialLinks: SocialLinks = SocialLinks(),
    val updatedAt: Timestamp? = null,
    val updatedBy: String? = null
)
