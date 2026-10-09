package com.westly.nbms.features.reports

import com.google.firebase.Timestamp
import com.westly.nbms.features.bookings.Booking
import com.westly.nbms.features.finance.ApprovalStatus
import com.westly.nbms.features.finance.RevenueCategory
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.finance.SourceCollection
import com.westly.nbms.features.rooms.Room
import java.time.Instant

/** Test builders for the Annual Report tests. Every date is an ISO instant such as "2026-10-15T10:00:00Z". */
internal fun annualTs(iso: String): Timestamp = Timestamp(Instant.parse(iso).epochSecond, 0)

internal fun annualTxn(
    id: String,
    amount: Double,
    date: String? = "2026-10-15T10:00:00Z",
    status: ApprovalStatus = ApprovalStatus.APPROVED
) = RevenueTransaction(
    id = id, source = SourceCollection.PAYMENTS, category = RevenueCategory.ROOM, typeLabel = "Payment", guestName = "Guest",
    amount = amount, paymentMethod = "cash", date = date?.let { Instant.parse(it) }, recordedBy = null,
    recordedByName = "Staff", approvalStatus = status, approvedBy = null, approvedByName = null,
    approvedAt = null, rejectedReason = null
)

internal fun annualExpense(
    id: String,
    amount: Double,
    date: String? = "2026-10-15T10:00:00Z",
    isDeleted: Boolean = false
) = Expense(id = id, title = "Item $id", amount = amount, date = date?.let { annualTs(it) }, isDeleted = isDeleted)

internal fun annualBooking(
    id: String,
    createdAt: String? = "2026-10-15T10:00:00Z",
    status: String = "pending",
    isDeleted: Boolean = false
) = Booking(id = id, status = status, createdAt = createdAt?.let { annualTs(it) }, isDeleted = isDeleted)

internal fun annualRoom(id: String, isDeleted: Boolean = false) = Room(id = id, number = id, isDeleted = isDeleted)
