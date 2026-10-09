package com.westly.nbms.features.reports

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/** Expense categories. `key` is what is stored in Firestore, `label` is what the person sees. */
enum class ExpenseCategory(val key: String, val label: String) {
    UTILITIES("utilities", "Utilities"),
    MAINTENANCE("maintenance", "Maintenance"),
    SUPPLIES("supplies", "Supplies"),
    PAYROLL("payroll", "Payroll"),
    MARKETING("marketing", "Marketing"),
    FOOD_BEVERAGE("food_beverage", "Food Beverage"),
    EQUIPMENT("equipment", "Equipment"),
    OTHER("other", "Other");

    companion object {
        /** A null or unknown key becomes [OTHER]. */
        fun fromKey(key: String?): ExpenseCategory = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** How an expense was paid. A null or unknown key becomes [CASH]. */
enum class ExpensePaymentMethod(val key: String, val label: String) {
    CASH("cash", "Cash"),
    BANK_TRANSFER("bank_transfer", "Bank Transfer"),
    CARD("card", "Card"),
    CHECK("check", "Check");

    companion object {
        fun fromKey(key: String?): ExpensePaymentMethod = entries.firstOrNull { it.key == key } ?: CASH
    }
}

/** Firestore `businesses/{bid}/expenses/{id}`. Money is in naira (major unit). */
data class Expense(
    @DocumentId val id: String = "",
    val title: String = "",
    val amount: Double = 0.0,
    val category: String = "other",
    val date: Timestamp? = null,
    val description: String? = null,
    val paymentMethod: String = "cash",
    val recordedBy: String? = null,
    val recordedByName: String = "",
    val createdAt: Timestamp? = null,
    val isDeleted: Boolean = false
)
