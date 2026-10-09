package com.westly.nbms.features.finance

import java.math.BigDecimal
import java.time.ZoneId

/** The first line of every payments export. Every cell, the header too, is double-quoted. */
internal const val PAYMENTS_CSV_HEADER =
    "\"Date\",\"Guest\",\"Amount\",\"Payment Method\",\"Type\",\"Status\",\"Recorded By\",\"Approved By\""

/** Wraps a value in double quotes and doubles every quote inside it, so commas, quotes and line breaks stay in one cell. */
internal fun paymentsCsvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

/** "5000" for 5000.0 and "5000.5" for 5000.5: plain digits with no symbol and no thousands separators, so a spreadsheet can add them up. */
internal fun paymentsCsvAmount(amount: Double): String =
    if (amount.isNaN() || amount.isInfinite()) "0" else BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()

/** "2026-10-07" in the business time zone, or an empty cell when the payment has no date yet. */
internal fun paymentsCsvDate(doc: PaymentDoc, zone: ZoneId): String =
    paymentInstant(doc)?.atZone(zone)?.toLocalDate()?.toString().orEmpty()

/** One line: Date, Guest, Amount, Payment Method, Type, Status, Recorded By, Approved By. Method and type are the stored (raw) values. */
internal fun paymentsCsvRow(doc: PaymentDoc, dateText: String): String =
    listOf(
        dateText,
        doc.guestName.orEmpty(),
        paymentsCsvAmount(doc.amount),
        doc.paymentMethod.orEmpty(),
        doc.type.orEmpty(),
        paymentStatusKey(doc.approvalStatus),
        doc.recordedByName.orEmpty(),
        doc.approvedByName.orEmpty()
    ).joinToString(",") { paymentsCsvCell(it) }

/** The whole file: the header, then one line per row. Callers pass the FILTERED list. */
internal fun buildPaymentsCsv(rows: List<PaymentDoc>, dateText: (PaymentDoc) -> String): String =
    (listOf(PAYMENTS_CSV_HEADER) + rows.map { paymentsCsvRow(it, dateText(it)) }).joinToString("\n")

/** "payments-2026-10.csv" for a month written as yyyy-MM, or "payments-all.csv" when no month is chosen. */
internal fun paymentsCsvFileName(month: String): String {
    val clean = month.trim()
    return if (clean.isEmpty()) "payments-all.csv" else "payments-$clean.csv"
}
