package com.westly.nbms.features.finance

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The first line of the transactions export. The data cells below it are always double-quoted. */
internal const val TRANSACTIONS_CSV_HEADER =
    "Date & Time,Guest,Type,Category,Amount,Method,Status,Recorded By,Approved By,Approval Date,Rejection Reason"

private val TRANSACTIONS_CSV_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

/** Wraps a value in double quotes and doubles every quote inside it, so commas, quotes and line breaks stay in one cell. */
internal fun transactionsCsvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

/** "5000" for 5000.0 and "5000.5" for 5000.5: plain digits, no symbol, no thousands separators. */
internal fun transactionsCsvAmount(amount: Double): String =
    if (amount.isNaN() || amount.isInfinite()) "0" else BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()

/** "2026-10-07 14:30" in the business time zone, or an empty cell when there is no date. */
internal fun transactionsCsvDateTime(date: Instant?, zone: ZoneId): String =
    date?.atZone(zone)?.format(TRANSACTIONS_CSV_DATE_TIME).orEmpty()

/** "2026-10-07" in the business time zone, or an empty cell when there is no date. */
internal fun transactionsCsvDay(date: Instant?, zone: ZoneId): String =
    date?.atZone(zone)?.toLocalDate()?.toString().orEmpty()

/** "Pending", "Approved" or "Rejected": the same words as the status badge. */
internal fun transactionsStatusLabel(status: ApprovalStatus): String = when (status) {
    ApprovalStatus.PENDING -> "Pending"
    ApprovalStatus.APPROVED -> "Approved"
    ApprovalStatus.REJECTED -> "Rejected"
}

/**
 * One line: Date & Time, Guest, Type, Category, Amount, Method, Status, Recorded By, Approved By, Approval Date, Rejection Reason.
 * Method is the stored value. Approved By, Approval Date and Rejection Reason are empty cells when there is nothing to show.
 */
internal fun transactionsCsvRow(txn: RevenueTransaction, zone: ZoneId): String =
    listOf(
        transactionsCsvDateTime(txn.date, zone),
        txn.guestName,
        txn.typeLabel,
        txn.category.label,
        transactionsCsvAmount(txn.amount),
        txn.paymentMethod,
        transactionsStatusLabel(txn.approvalStatus),
        txn.recordedByName,
        txn.approvedByName.orEmpty(),
        transactionsCsvDay(txn.approvedAt, zone),
        txn.rejectedReason.orEmpty()
    ).joinToString(",") { transactionsCsvCell(it) }

/** The whole file: the header, then one line per row. Callers pass the FILTERED list. */
internal fun buildTransactionsCsv(rows: List<RevenueTransaction>, zone: ZoneId): String =
    (listOf(TRANSACTIONS_CSV_HEADER) + rows.map { transactionsCsvRow(it, zone) }).joinToString("\n")

/** "transactions-2026-10-01_to_2026-10-31.csv". */
internal fun transactionsCsvFileName(start: LocalDate, end: LocalDate): String = "transactions-${start}_to_${end}.csv"
