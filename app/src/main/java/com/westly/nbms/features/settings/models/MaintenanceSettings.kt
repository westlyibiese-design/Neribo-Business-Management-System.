package com.westly.nbms.features.settings.models

import com.google.firebase.Timestamp

const val DEFAULT_MAINTENANCE_TITLE = "We'll Be Right Back"
const val DEFAULT_MAINTENANCE_TEXT =
    "We're performing scheduled maintenance. Thank you for your patience — we'll be back shortly."

/** Values of [MaintenanceSettings.target]. */
object MaintenanceTarget {
    const val NONE = "none"
    const val PUBLIC = "public"
    const val ADMIN = "admin"
    const val BOTH = "both"
    val all = listOf(NONE, PUBLIC, ADMIN, BOTH)
}

/** `businesses/{bid}/settings/maintenance`. */
data class MaintenanceSettings(
    val target: String = MaintenanceTarget.NONE,
    val title: String = DEFAULT_MAINTENANCE_TITLE,
    val message: String = DEFAULT_MAINTENANCE_TEXT,
    /** ISO-8601 instant text, or null. */
    val estimatedReturn: String? = null,
    val imageUrl: String? = null,
    val updatedAt: Timestamp? = null,
    val updatedBy: String? = null,
    val updatedByName: String? = null
)
