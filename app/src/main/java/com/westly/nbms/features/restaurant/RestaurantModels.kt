package com.westly.nbms.features.restaurant

/** The five menu categories. [key] is what is stored in the menu document; [label] is what the person reads. */
enum class MenuCategory(val key: String, val label: String) {
    BREAKFAST("breakfast", "Breakfast"),
    LUNCH("lunch", "Lunch"),
    DINNER("dinner", "Dinner"),
    DRINKS("drinks", "Drinks"),
    DESSERTS("desserts", "Desserts");

    companion object {
        /** The category with this stored key, or null for a missing or unknown key. */
        fun fromKey(key: String?): MenuCategory? = entries.firstOrNull { it.key == key }
    }
}

/** `category` holds the MenuCategory key (a String), exactly as stored in the document. */
data class MenuItem(
    val id: String = "",
    val name: String = "",
    val image: String = "",
    val description: String = "",
    val price: Double = 0.0,
    val category: String = "breakfast",
    val available: Boolean = true
)

/** The single change one save applies to the live array. */
sealed interface MenuChange {
    data class Add(val item: MenuItem) : MenuChange
    data class Replace(val item: MenuItem) : MenuChange          // by id
    data class Remove(val id: String) : MenuChange
    data class SetAvailable(val id: String, val available: Boolean) : MenuChange
}
