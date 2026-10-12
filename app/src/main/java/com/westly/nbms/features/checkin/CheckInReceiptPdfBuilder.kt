package com.westly.nbms.features.checkin

import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.westly.nbms.core.util.Branding
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.checkout.ReceiptKit
import com.westly.nbms.features.checkout.ReceiptKit.Glyph
import com.westly.nbms.features.checkout.contactItems
import com.westly.nbms.features.checkout.receiptDate
import com.westly.nbms.features.checkout.receiptDateTime
import java.io.OutputStream

/**
 * Draws the "Stay Receipt" a guest gets at check-in: a dark header with the hotel wordmark, a white sheet that says
 * "Stay Confirmed", the guest and room details, the payment summary, a payment box (green when paid, amber when the
 * room is paid at check-out) with a QR code, and the hotel's contact details. One page whose height follows its content.
 */
internal object CheckInReceiptPdfBuilder {

    private const val HEADER_H = 128f
    private const val SHEET_TOP = 104f
    private const val SHEET_L = 16f
    private const val SHEET_R = 464f
    private const val IN_L = 36f
    private const val IN_R = 444f
    private const val BOTTOM_H = 36f
    private const val SUMMARY_TOP = 382f
    private const val SUMMARY_H = 128f
    private const val PAY_TOP = 524f
    private const val PAY_H = 104f
    private const val CONTACT_TOP = 642f
    private const val COLUMN_W = 190f

    /** Writes the PDF to [out]. The caller closes the stream. */
    fun build(data: CheckInReceiptData, out: OutputStream) {
        val rows = contactRows(data)
        val contactHeight = rows.sumOf { it.height.toDouble() }.toFloat()
        val sheetBottom = CONTACT_TOP + 12f + contactHeight + 40f
        val height = sheetBottom + 16f + BOTTOM_H
        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(ReceiptKit.W.toInt(), Math.ceil(height.toDouble()).toInt(), 1).create()
            val page = doc.startPage(info)
            draw(page.canvas, data, rows, sheetBottom, height)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** One line of the contact area: up to two items side by side, each wrapped to at most two lines. */
    private class ContactRow(val cells: List<List<String>>) {
        val height: Float = (if (cells.any { it.size > 1 }) 30f else 18f)
    }

    private fun contactRows(d: CheckInReceiptData): List<ContactRow> {
        val paint = ReceiptKit.paint(8.5f, false, ReceiptKit.INK)
        return contactItems(d.hotel)
            .map { ReceiptKit.wrap(it, paint, COLUMN_W - 14f, 2) }
            .chunked(2)
            .map { ContactRow(it) }
    }

    private fun draw(c: Canvas, d: CheckInReceiptData, rows: List<ContactRow>, sheetBottom: Float, height: Float) = with(ReceiptKit) {
        c.drawColor(0xFFF1F2F5.toInt())
        val zone = d.zone
        val soft = 0xFFB8C2D4.toInt()

        // ---- Header ----
        c.header(HEADER_H)
        c.wordmark(d.businessName, d.hotel.tagline, 150f, 236f, 62f)
        c.textLeft("STAY RECEIPT", 326f, 56f, paint(11f, true, GOLD, spacing = 1.6f))
        c.textLeft("Your stay, our priority.", 326f, 74f, paint(8.5f, false, soft))

        // ---- The white sheet ----
        c.card(SHEET_L, SHEET_TOP, SHEET_R, sheetBottom, 18f, WHITE, LIGHT)

        // Stay Confirmed
        c.badge(68f, 160f, 30f, GOLD, WHITE, Glyph.BED)
        c.textLeft("CHECK-IN RECEIPT", 112f, 142f, paint(8.5f, true, NAVY, spacing = 1.6f))
        c.textLeft("Stay Confirmed", 112f, 170f, serif(25f, false, NAVY))
        val money = Format.currency(d.total, d.currencySymbol)
        val intro = if (d.paid) {
            "Thank you for choosing ${d.businessName}. Your payment has been received and your stay is confirmed."
        } else {
            "Thank you for choosing ${d.businessName}. Your stay is confirmed; the room charge of $money is due at check-out."
        }
        val introPaint = paint(9f, false, GREY)
        wrap(intro, introPaint, 196f, 3).forEachIndexed { i, line -> c.textLeft(line, 112f, 188f + i * 12f, introPaint) }

        c.vLine(318f, 134f, 214f, GOLD, 1f)
        c.textLeft("Receipt No.", 332f, 148f, paint(8.5f, false, GREY))
        val numberPaint = shrink(d.receiptNumber, paint(14f, true, NAVY), IN_R - 332f, 9f)
        c.textLeft(fit(d.receiptNumber, numberPaint, IN_R - 332f), 332f, 166f, numberPaint)
        c.textLeft("Date & Time", 332f, 192f, paint(8.5f, false, GREY))
        val whenText = receiptDateTime(d.issuedAt, zone)
        val whenPaint = shrink(whenText, paint(10.5f, true, NAVY), IN_R - 332f, 7.5f)
        c.textLeft(fit(whenText, whenPaint, IN_R - 332f), 332f, 208f, whenPaint)

        // Guest and room details
        val detailsTop = 236f
        c.card(IN_L, detailsTop, IN_R, detailsTop + 132f, 12f, CARD, LIGHT)
        c.badge(62f, detailsTop + 26f, 18f, NAVY, GOLD, Glyph.PERSON)
        c.textLeft("Guest Name", 88f, detailsTop + 22f, paint(9.5f, false, GREY))
        val namePaint = shrink(d.guestName, paint(15f, true, NAVY, family = Typeface.SERIF), 140f, 9f)
        c.textLeft(fit(d.guestName, namePaint, 140f), 88f, detailsTop + 42f, namePaint)
        val small = paint(9.5f, false, INK)
        var infoY = detailsTop + 76f
        d.guestPhone?.let {
            c.textLeft(fit(it, small, 170f), 54f, infoY, small)
            infoY += 18f
        }
        d.guestEmail?.let { c.textLeft(fit(it, small, 170f), 54f, infoY, small) }

        c.vLine(238f, detailsTop + 14f, detailsTop + 118f, GOLD, 1f)
        c.badge(278f, detailsTop + 26f, 18f, NAVY, GOLD, Glyph.BED)
        c.textLeft("Room Details", 304f, detailsTop + 22f, paint(9.5f, false, GREY))
        c.textLeft(fit(d.roomNumber, paint(15f, true, NAVY), 130f), 304f, detailsTop + 42f, paint(15f, true, NAVY))
        val labelPaint = paint(9f, false, GREY)
        listOf(
            "Room Type" to (d.roomType ?: "—"),
            "Check-In" to receiptDateTime(d.checkIn, zone),
            "Check-Out" to receiptDateTime(d.checkOut, zone),
            "Nights" to d.nights.toString()
        ).forEachIndexed { i, (label, value) ->
            val base = detailsTop + 68f + i * 17f
            c.textLeft(label, 252f, base, labelPaint)
            val valuePaint = shrink(value, paint(9.5f, false, INK), IN_R - 326f - 8f, 7.5f)
            c.textLeft(fit(value, valuePaint, IN_R - 326f - 8f), 326f, base, valuePaint)
        }

        // Payment summary
        c.card(IN_L, SUMMARY_TOP + 22f, IN_R, SUMMARY_TOP + SUMMARY_H, 10f, WHITE, LIGHT)
        c.card(IN_L, SUMMARY_TOP, IN_R, SUMMARY_TOP + 32f, 8f, NAVY)
        c.badge(56f, SUMMARY_TOP + 16f, 11f, NAVY_LIGHT, GOLD, Glyph.DOC)
        c.textLeft("Payment Summary", 74f, SUMMARY_TOP + 21f, paint(12.5f, true, WHITE))
        c.textRight("Amount", IN_R - 14f, SUMMARY_TOP + 21f, paint(10f, false, WHITE))

        val rowTop = SUMMARY_TOP + 32f
        val rateLabel = "Room Rate" + (d.roomType?.let { " ($it)" } ?: "")
        c.textLeft(fit(rateLabel, paint(11f, true, NAVY), 270f), 50f, rowTop + 20f, paint(11f, true, NAVY))
        val nightWord = if (d.nights == 1) "night" else "nights"
        val note = "${d.nights} $nightWord × ${Format.currency(d.ratePerNight, d.currencySymbol)} " +
            "(${receiptDate(d.checkIn, zone)} – ${receiptDate(d.checkOut, zone)})"
        val notePaint = paint(8.5f, false, GREY)
        c.textLeft(fit(note, notePaint, 300f), 50f, rowTop + 34f, notePaint)
        c.textRight(money, IN_R - 14f, rowTop + 24f, paint(11f, true, NAVY))
        c.hLine(50f, IN_R - 14f, rowTop + 50f, LIGHT, 0.8f)
        c.textLeft(if (d.paid) "Total Paid" else "Total Due", 50f, rowTop + 78f, paint(14f, true, NAVY))
        val totalPaint = shrink(money, serif(21f, true, NAVY), 190f, 12f)
        c.textRight(money, IN_R - 14f, rowTop + 80f, totalPaint)

        // Payment box
        val box = if (d.paid) MINT else AMBER_BG
        val ink = if (d.paid) GREEN else AMBER
        c.card(IN_L, PAY_TOP, IN_R, PAY_TOP + PAY_H, 14f, box, if (d.paid) 0xFFCFE6D8.toInt() else 0xFFF3DDB5.toInt())
        c.badge(68f, PAY_TOP + 34f, 18f, ink, WHITE, if (d.paid) Glyph.CHECK else Glyph.EXCLAIM)
        c.textLeft(if (d.paid) "PAYMENT CONFIRMED" else "PAYMENT PENDING", 96f, PAY_TOP + 26f, paint(8.5f, true, ink, spacing = 1.4f))
        c.textLeft(if (d.paid) "Your stay is all set!" else "Pay at check-out", 96f, PAY_TOP + 48f, serif(17f, true, ink))
        val payLine = if (d.paid) {
            "We've received your payment of $money."
        } else {
            "Payment of $money is due at check-out."
        }
        val payPaint = paint(9f, false, INK)
        wrap(payLine, payPaint, 210f, 2).forEachIndexed { i, line -> c.textLeft(line, 96f, PAY_TOP + 66f + i * 12f, payPaint) }
        c.card(352f, PAY_TOP + 8f, 432f, PAY_TOP + 88f, 8f, WHITE)
        c.qr(d.receiptNumber, 358f, PAY_TOP + 14f, 68f)
        c.textCenter("Booking reference", 392f, PAY_TOP + 99f, paint(8f, false, ink))

        // The hotel's details and a closing line
        var y = CONTACT_TOP
        if (rows.isNotEmpty()) c.hLine(IN_L, IN_R, y, LIGHT, 1f)
        y += 14f
        val contactPaint = paint(8.5f, false, INK)
        for (row in rows) {
            row.cells.forEachIndexed { col, lines ->
                val x = IN_L + col * (COLUMN_W + 28f)
                c.dot(x + 3f, y - 3f)
                lines.forEachIndexed { i, text -> c.textLeft(text, x + 14f, y + i * 12.5f, contactPaint) }
            }
            y += row.height
        }
        val closing = d.hotel.tagline ?: "We look forward to making your stay exceptional."
        val handwriting = shrink(closing, script(16f, GOLD), IN_R - IN_L, 10f)
        c.textCenter(fit(closing, handwriting, IN_R - IN_L), W / 2f, sheetBottom - 14f, handwriting)

        c.footerBar(height - BOTTOM_H, BOTTOM_H, Branding.COPYRIGHT)
    }
}
