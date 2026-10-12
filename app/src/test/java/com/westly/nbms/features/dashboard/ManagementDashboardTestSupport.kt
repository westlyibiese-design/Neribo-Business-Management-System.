package com.westly.nbms.features.dashboard

import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.finance.ApprovalStatus
import com.westly.nbms.features.finance.RevenueCategory
import com.westly.nbms.features.finance.RevenueTransaction
import com.westly.nbms.features.finance.SourceCollection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal val DASH_LAGOS: ZoneId = ZoneId.of("Africa/Lagos")

/** Monday 12 October 2026, 10:00 in Lagos. */
internal val DASH_NOW: Instant = dashAt("2026-10-12T10:00:00")

internal fun dashAt(local: String, zone: ZoneId = DASH_LAGOS): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

internal class RecordingNavigator : ShellNavigator {
    val opened = mutableListOf<String>()
    override fun open(link: String) { opened += link }
}

internal fun dashTxn(id: String, amount: Double, date: Instant?, status: ApprovalStatus = ApprovalStatus.APPROVED) = RevenueTransaction(
    id = id, source = SourceCollection.PAYMENTS, category = RevenueCategory.ROOM, typeLabel = "Room", guestName = "G",
    amount = amount, paymentMethod = "cash", date = date, recordedBy = null, recordedByName = "", approvalStatus = status,
    approvedBy = null, approvedByName = null, approvedAt = null, rejectedReason = null
)

internal fun dashBooking(
    id: String,
    guest: String? = "Guest $id",
    room: String? = "101",
    status: String? = "confirmed",
    checkIn: Instant? = null,
    checkOut: Instant? = null,
    createdAt: Instant? = null,
    source: String? = null,
    deleted: Boolean = false
) = DashBooking(
    id = id, guestName = guest, roomNumber = room, status = status, source = source,
    checkIn = checkIn, checkOut = checkOut, createdAt = createdAt, isDeleted = deleted
)
