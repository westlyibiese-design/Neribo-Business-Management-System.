package com.westly.nbms.features.gym

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.westly.nbms.core.util.Format

/** The four membership states. `key` is what is stored, `label` is what the person reads. */
enum class MembershipStatus(val key: String, val label: String) {
    ACTIVE("active", "Active"),
    EXPIRED("expired", "Expired"),
    SUSPENDED("suspended", "Suspended"),
    CANCELLED("cancelled", "Cancelled");

    companion object {
        /** null for an unknown key. */
        fun fromKey(key: String?): MembershipStatus? = entries.firstOrNull { it.key == key }
    }
}

/** Firestore `businesses/{bid}/gym_members/{id}`. Money is in naira (major unit). Field names are the ones Westly used. */
data class GymMember(
    @DocumentId val id: String = "",
    val name: String = "",
    val email: String? = null,
    val phone: String? = null,
    val roomNumber: String? = null,
    val packageId: String = "",
    val packageName: String = "",
    val packagePrice: Double = 0.0,
    val durationDays: Int = 0,
    val startDate: Timestamp? = null,
    val endDate: Timestamp? = null,
    val status: String = "active",              // active | expired | suspended | cancelled
    val statusReason: String? = null,
    val notes: String? = null,
    val visitCount: Int = 0,
    val lastVisitAt: Timestamp? = null,
    val activeVisitId: String? = null,
    val registeredBy: String = "",
    val registeredByName: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    // The annotation keeps the stored field name "isDeleted" (Firestore would otherwise call it "deleted").
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** Firestore `businesses/{bid}/gym_attendance/{id}`: one document per visit. */
data class GymVisit(
    @DocumentId val id: String = "",
    val memberId: String = "",
    val memberName: String = "",
    val checkInAt: Timestamp? = null,
    val checkOutAt: Timestamp? = null,
    val checkedInBy: String = "",
    val checkedInByName: String = "",
    val checkedOutBy: String? = null,
    val checkedOutByName: String? = null,
    val dateKey: String = "",                   // "yyyy-MM-dd" in the business time zone
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false
)

/** One membership package from `cms_content/gym` (read only in this phase; Phase 30 edits them). */
data class GymPackage(
    val id: String,
    val name: String,
    val price: Double,
    /** Free-text label such as "Monthly", "Weekly", "Quarterly", "Annual". */
    val duration: String
)

/** "Monthly Pass — ₦15,000 (Monthly)": the text of one entry in the package drop-downs. */
internal fun packageOptionLabel(pkg: GymPackage, currencySymbol: String): String =
    "${pkg.name} — ${Format.currency(pkg.price, currencySymbol)} (${pkg.duration})"

/**
 * The raw `cms_content/gym` document. `data` is read as "anything" so a missing field, a field that is not a map, or odd
 * entries never stop the page from loading (see [parseGymPackages]). Public with defaults so Firestore can build it.
 */
class RawGymDocument(val data: Any? = null)

// ── tolerant reading of the package list ──

/** Every usable package in `data.packages`, in order. Anything that is not a map with an id, a name and a price is skipped. */
internal fun parseGymPackages(data: Any?): List<GymPackage> {
    val map = data as? Map<*, *> ?: return emptyList()
    val list = map["packages"] as? List<*> ?: return emptyList()
    return list.mapNotNull { parseGymPackage(it) }
}

internal fun parseGymPackage(raw: Any?): GymPackage? {
    val map = raw as? Map<*, *> ?: return null
    val id = gymIdText(map["id"]) ?: return null
    val name = (map["name"] as? String)?.trim().orEmpty()
    if (name.isEmpty()) return null
    val price = gymNumber(map["price"]) ?: return null
    val duration = (map["duration"] as? String)?.trim().orEmpty()
    return GymPackage(id = id, name = name, price = price, duration = duration)
}

/** A text id, or a whole number written as text ("3"). Blank or any other type is null. */
private fun gymIdText(raw: Any?): String? = when (raw) {
    is String -> raw.trim().ifEmpty { null }
    is Number -> {
        val d = raw.toDouble()
        if (d.isNaN() || d.isInfinite()) null else if (d % 1.0 == 0.0) d.toLong().toString() else raw.toString()
    }
    else -> null
}

/** A number, or text that holds a number. Negative, NaN and infinite values are not prices. */
private fun gymNumber(raw: Any?): Double? {
    val value = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    } ?: return null
    return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
}

/** A problem with a clear sentence for the person (shown as the message of the failure toast). */
class GymException(message: String) : Exception(message)
