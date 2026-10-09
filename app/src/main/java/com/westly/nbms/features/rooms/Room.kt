package com.westly.nbms.features.rooms

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.westly.nbms.core.design.BadgeTone

enum class RoomStatus(val key: String) {
    AVAILABLE("available"),
    OCCUPIED("occupied"),
    RESERVED("reserved"),
    CLEANING("cleaning"),
    MAINTENANCE("maintenance"),
    OUT_OF_SERVICE("out_of_service");

    /** Plain-words name for chips and dropdowns: "Out of Service". */
    val label: String
        get() = when (this) {
            AVAILABLE -> "Available"
            OCCUPIED -> "Occupied"
            RESERVED -> "Reserved"
            CLEANING -> "Cleaning"
            MAINTENANCE -> "Maintenance"
            OUT_OF_SERVICE -> "Out of Service"
        }

    companion object {
        /** null for an unknown key. */
        fun fromKey(key: String?): RoomStatus? = entries.firstOrNull { it.key == key }
    }
}

enum class Cleanliness(val key: String) {
    CLEAN("clean"),
    DAILY_CLEANING_DUE("daily_cleaning_due"),
    CHECKOUT_CLEANING_DUE("checkout_cleaning_due"),
    CLEANING_IN_PROGRESS("cleaning_in_progress"),
    DIRTY("dirty");

    companion object {
        /** null for an unknown key. */
        fun fromKey(key: String?): Cleanliness? = entries.firstOrNull { it.key == key }
    }
}

enum class RoomEvent { CHECK_IN, CHECK_OUT, START_CLEANING, FINISH_CLEANING, MAINTENANCE, BACK_TO_SERVICE }

/** Firestore `businesses/{bid}/rooms/{id}`. */
data class Room(
    @DocumentId val id: String = "",
    val number: String = "",
    val name: String? = null,
    val type: String = "Standard Room",
    val price: Double = 0.0,
    val capacity: Int = 2,
    val floor: String = "1",
    val description: String = "",
    val amenities: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val status: String = "available",
    val cleanliness: String? = null,
    val checkoutOverdue: Boolean = false,
    val currentBookingId: String? = null,
    // Firestore would call this property "deleted" (Java bean rule for Boolean "is…" getters);
    // the annotation keeps the stored field name "isDeleted" that every phase uses.
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false,
    val createdAt: Timestamp? = null,
    val statusUpdatedAt: Timestamp? = null,
    val cleanlinessUpdatedAt: Timestamp? = null
)

data class RoomDisplayStatus(
    val label: String,
    val tone: BadgeTone,
    val occupancyStatus: RoomStatus,
    val cleanliness: Cleanliness,
    val checkoutOverdue: Boolean
)

/** The pill texts of Appendix B section 9.3. */
object RoomLabels {
    const val MAINTENANCE = "Maintenance"
    const val OUT_OF_SERVICE = "Out of Service"
    const val RESERVED = "Reserved"
    const val OCCUPIED_OVERDUE = "Occupied · Checkout Overdue"
    const val OCCUPIED_CLEANING_IN_PROGRESS = "Occupied · Cleaning in Progress"
    const val OCCUPIED_CLEANING_DUE = "Occupied · Cleaning Due"
    const val OCCUPIED_CLEAN = "Occupied · Clean"
    const val CLEANING_IN_PROGRESS = "Cleaning in Progress"
    const val DIRTY = "Dirty · Needs Cleaning"
    const val AVAILABLE = "Clean · Available"
}

/** Status of the room as a [RoomStatus]; an unknown or missing value counts as available (Westly default). */
fun Room.statusOrDefault(): RoomStatus = RoomStatus.fromKey(status) ?: RoomStatus.AVAILABLE

/** What the website and cards show first: the room name, or the room type when there is no name. */
fun Room.displayName(): String = name?.takeIf { it.isNotBlank() } ?: type
