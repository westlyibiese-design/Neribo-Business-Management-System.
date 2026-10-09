package com.westly.nbms.features.finance

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** A transaction for the Revenue page tests. Defaults: an approved Room payment of 1,000 with no date. */
internal fun revenueScreenTxn(
    id: String,
    category: RevenueCategory = RevenueCategory.ROOM,
    amount: Double = 1_000.0,
    status: ApprovalStatus = ApprovalStatus.APPROVED,
    date: Instant? = null
): RevenueTransaction = RevenueTransaction(
    id = id,
    source = when (category) {
        RevenueCategory.SALES -> SourceCollection.SALES
        RevenueCategory.RESTAURANT -> SourceCollection.ORDERS
        RevenueCategory.BAR -> SourceCollection.BAR_ORDERS
        RevenueCategory.LAUNDRY -> SourceCollection.LAUNDRY_REQUESTS
        else -> SourceCollection.PAYMENTS
    },
    category = category,
    typeLabel = category.label,
    guestName = "Guest $id",
    amount = amount,
    paymentMethod = "cash",
    date = date,
    recordedBy = "u1",
    recordedByName = "Staff",
    approvalStatus = status,
    approvedBy = null,
    approvedByName = null,
    approvedAt = null,
    rejectedReason = null
)

/** This part's own fake ledger (it does not use Part 16A-1's test files). The page only ever calls [observe]. */
internal class RevenueScreenFakeLedger(
    initial: Resource<List<RevenueTransaction>> = Resource.Success(emptyList())
) : RevenueLedger {
    val flow = MutableStateFlow(initial)

    override fun observe(): Flow<Resource<List<RevenueTransaction>>> = flow

    override suspend fun approve(txn: RevenueTransaction) {
        throw AssertionError("The Revenue page must never approve a payment.")
    }

    override suspend fun reject(txn: RevenueTransaction, reason: String?) {
        throw AssertionError("The Revenue page must never reject a payment.")
    }
}
