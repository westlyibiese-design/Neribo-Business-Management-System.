package com.westly.nbms.features.inventory

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

/** Firestore `businesses/{bid}/inventory/{id}`. Money is in naira (major unit). Field names are the ones Westly used. */
data class InventoryItem(
    @DocumentId val id: String = "",
    val name: String = "",
    val category: String = "hotel_supplies",   // drinks | toiletries | cleaning_supplies | hotel_supplies | food | equipment | other
    val quantity: Int = 0,
    val minStock: Int = 0,
    val unit: String = "pcs",
    val costPerUnit: Double = 0.0,
    val supplier: String? = null,
    // Firestore would call this property "deleted" (Java bean rule for Boolean "is…" getters);
    // the annotation keeps the stored field name "isDeleted" that every phase uses.
    @get:PropertyName("isDeleted") val isDeleted: Boolean = false,
    val lastRestocked: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

/** The seven Westly categories. `key` is what is stored, `label` is what the person reads ("Cleaning Supplies"). */
enum class InventoryCategory(val key: String, val label: String) {
    DRINKS("drinks", "Drinks"),
    TOILETRIES("toiletries", "Toiletries"),
    CLEANING_SUPPLIES("cleaning_supplies", "Cleaning Supplies"),
    HOTEL_SUPPLIES("hotel_supplies", "Hotel Supplies"),
    FOOD("food", "Food"),
    EQUIPMENT("equipment", "Equipment"),
    OTHER("other", "Other");

    companion object {
        /** null for an unknown key. */
        fun fromKey(key: String?): InventoryCategory? = entries.firstOrNull { it.key == key }
    }
}

/** "cleaning_supplies" -> "Cleaning Supplies": underscores become spaces and every word is capitalised. A blank value is "—". */
internal fun inventoryWords(raw: String?): String {
    val clean = raw?.trim().orEmpty()
    if (clean.isEmpty()) return "—"
    return clean.replace('_', ' ').split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}
