package com.westly.nbms.features.finance

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

enum class RevenueCategory(val label: String) {
    ROOM("Room Revenue"),
    RESTAURANT("Restaurant Revenue"),
    SALES("Sales Revenue"),
    BAR("Bar Revenue"),
    LAUNDRY("Laundry Revenue"),
    OTHER("Other Income")
}

enum class SourceCollection(val path: String) {
    PAYMENTS("payments"),
    SALES("sales"),
    ORDERS("orders"),
    BAR_ORDERS("bar_orders"),
    LAUNDRY_REQUESTS("laundry_requests")
}

data class RevenueTransaction(
    val id: String,
    val source: SourceCollection,
    val category: RevenueCategory,
    val typeLabel: String,
    val guestName: String,
    val amount: Double,
    val paymentMethod: String,
    val date: Instant?,
    val recordedBy: String?,
    val recordedByName: String,
    val approvalStatus: ApprovalStatus,
    val approvedBy: String?,
    val approvedByName: String?,
    val approvedAt: Instant?,
    val rejectedReason: String?
)

enum class DateRangePreset { TODAY, WEEK, MONTH, YEAR, CUSTOM }

data class DailyRecord(
    val date: LocalDate,
    val label: String,
    val room: Double,
    val restaurant: Double,
    val sales: Double,
    val bar: Double,
    val laundry: Double,
    val other: Double,
    val total: Double,
    val transactionCount: Int,
    val pending: Int,
    val approved: Int,
    val rejected: Int
)

/** The one place that turns payments, sales, orders, bar orders and laundry charges into revenue. Hilt @Singleton. */
interface RevenueLedger {
    /** payments + sales + orders + bar_orders + laundry_requests, merged, newest first. */
    fun observe(): Flow<Resource<List<RevenueTransaction>>>

    /** Writes the SOURCE document, audits "payment_approved", then Notifier.notifyPaymentApproved. */
    suspend fun approve(txn: RevenueTransaction)

    /** Writes the SOURCE document and audits "payment_rejected". */
    suspend fun reject(txn: RevenueTransaction, reason: String?)
}
