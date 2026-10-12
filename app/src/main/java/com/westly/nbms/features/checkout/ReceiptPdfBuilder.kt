package com.westly.nbms.features.checkout

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import com.westly.nbms.core.util.Branding
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.checkout.ReceiptKit.Glyph
import java.io.OutputStream

/**
 * Draws the "Customer Receipt" a guest gets at check-out: a navy header with the hotel wordmark, guest and stay cards,
 * the charges table with a gold "Total Paid" bar, a green payment box with a QR code, and the hotel's contact footer.
 * One tall page: its height follows the number of charge rows, so nothing is ever cut or split across pages.
 */
internal object ReceiptPdfBuilder {

    private const val HEADER_H = 136f
    private const val GAP = 18f
    private const val CARD_H = 142f
    private const val TABLE_HEAD_H = 28f
    private const val ROW_H = 38f
    private const val TOTAL_H = 42f
    private const val PAY_H = 100f
    private const val BOTTOM_H = 36f
    private const val CONTACT_X = 252f
    private const val CONTACT_W = 184f
    private const val THANKS_W = 190f

    /** Writes the PDF to [out]. The caller closes the stream. */
    fun build(data: ReceiptData, out: OutputStream) {
        val tops = tops(data)
        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(ReceiptKit.W.toInt(), Math.ceil(tops.height.toDouble()).toInt(), 1).create()
            val page = doc.startPage(info)
            draw(page.canvas, data, tops)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** The vertical position of every block, worked out once so the page height and the drawing always agree. */
    private class Tops(
        val cards: Float,
        val charges: Float,
        val table: Float,
        val rows: Float,
        val total: Float,
        val pay: Float,
        val foot: Float,
        val footHeight: Float,
        val height: Float,
        val contact: List<List<String>>
    )

    private fun tops(d: ReceiptData): Tops {
        val contactPaint = ReceiptKit.paint(8.5f, false, ReceiptKit.INK)
        val contact = contactItems(d.hotel).map { ReceiptKit.wrap(it, contactPaint, CONTACT_W - 12f, 2) }
        val contactHeight = contact.sumOf { it.size } * 12.5f + (contact.size - 1).coerceAtLeast(0) * 5f + 6f

        val cards = HEADER_H + GAP
        val charges = cards + CARD_H + 20f
        val table = charges + 40f
        val rows = table + TABLE_HEAD_H
        val total = rows + d.lines.size * ROW_H + 14f
        val pay = total + TOTAL_H + GAP
        val foot = pay + PAY_H + GAP
        val footHeight = maxOf(74f, contactHeight)
        val height = foot + footHeight + GAP + BOTTOM_H
        return Tops(cards, charges, table, rows, total, pay, foot, footHeight, height, contact)
    }

    private fun draw(c: Canvas, d: ReceiptData, t: Tops) = with(ReceiptKit) {
        c.drawColor(WHITE)
        val right = W - MARGIN
        val zone = d.zone

        // ---- Header ----
        c.header(HEADER_H)
        c.wordmark(d.businessName, d.hotel.tagline, 150f, 236f, 64f)
        c.textRight("CUSTOMER RECEIPT", right, 38f, paint(10.5f, true, GOLD, spacing = 1.6f))
        c.textRight("Receipt No.", right, 62f, paint(8f, false, 0xFFB8C2D4.toInt()))
        val numberPaint = shrink(d.receiptNumber, paint(15f, true, WHITE), 150f, 10f)
        c.textRight(fit(d.receiptNumber, numberPaint, 150f), right, 80f, numberPaint)
        c.hLine(300f, right, 91f, GOLD, 1f)
        c.textRight("Check Out", right, 106f, paint(8f, false, 0xFFB8C2D4.toInt()))
        c.textRight(receiptDateTime(d.checkOut, zone), right, 123f, paint(11.5f, true, WHITE))

        // ---- Guest and stay cards ----
        val colW = (W - 2 * MARGIN - 12f) / 2f
        val leftX = MARGIN
        val rightX = MARGIN + colW + 12f
        val y0 = t.cards

        c.card(leftX, y0, leftX + colW, y0 + CARD_H, 12f, CARD, LIGHT)
        c.badge(leftX + 30f, y0 + 30f, 17f, NAVY, GOLD, Glyph.PERSON)
        c.textLeft("Guest Details", leftX + 56f, y0 + 35f, paint(13f, true, NAVY))
        val namePaint = shrink(d.guestName, paint(16f, true, NAVY, family = android.graphics.Typeface.SERIF), colW - 28f, 10f)
        c.textLeft(fit(d.guestName, namePaint, colW - 28f), leftX + 14f, y0 + 76f, namePaint)
        var lineY = y0 + 98f
        val small = paint(9.5f, false, INK)
        d.guestPhone?.let {
            c.textLeft(fit(it, small, colW - 28f), leftX + 14f, lineY, small)
            lineY += 18f
        }
        d.guestEmail?.let {
            c.textLeft(fit(it, small, colW - 28f), leftX + 14f, lineY, small)
        }

        c.card(rightX, y0, rightX + colW, y0 + CARD_H, 12f, CARD, LIGHT)
        c.badge(rightX + 30f, y0 + 30f, 17f, NAVY, GOLD, Glyph.BED)
        c.textLeft("Stay Details", rightX + 56f, y0 + 35f, paint(13f, true, NAVY))
        val labelPaint = paint(9f, false, GREY)
        val valuePaint = paint(9.5f, false, INK)
        val stay = listOf(
            "Room Type" to (d.roomType ?: "—"),
            "Room Number" to d.roomNumber,
            "Check In" to receiptDateTime(d.checkIn, zone),
            "Check Out" to receiptDateTime(d.checkOut, zone),
            "Nights" to (d.nights?.toString() ?: "—")
        )
        stay.forEachIndexed { i, (label, value) ->
            val base = y0 + 64f + i * 17f
            c.textLeft(label, rightX + 14f, base, labelPaint)
            c.textLeft(fit(value, valuePaint, colW - 90f - 8f), rightX + 88f, base, valuePaint)
        }

        // ---- Charges ----
        c.badge(MARGIN + 17f, t.charges + 17f, 17f, NAVY, GOLD, Glyph.DOC)
        c.textLeft("Charges", MARGIN + 42f, t.charges + 23f, paint(17f, true, NAVY, family = android.graphics.Typeface.SERIF))

        c.card(MARGIN, t.table, right, t.table + TABLE_HEAD_H, 8f, NAVY)
        val head = paint(8.5f, true, WHITE, spacing = 0.8f)
        c.textLeft("DESCRIPTION", MARGIN + 14f, t.table + 18f, head)
        c.textRight("QTY", 296f, t.table + 18f, head)
        c.textRight("RATE", 372f, t.table + 18f, head)
        c.textRight("AMOUNT", right - 12f, t.table + 18f, head)

        val body = paint(11f, true, NAVY)
        val note = paint(8.5f, false, GREY)
        val cell = paint(10f, false, INK)
        val amountPaint = paint(10.5f, true, NAVY)
        d.lines.forEachIndexed { i, line ->
            val base = t.rows + i * ROW_H
            c.textLeft(fit(line.label, body, 200f), MARGIN + 14f, base + 17f, body)
            line.note?.let { c.textLeft(fit(it, note, 200f), MARGIN + 14f, base + 30f, note) }
            if (line.qty != null && line.rate != null) {
                c.textRight(line.qty.toString(), 296f, base + 20f, cell)
                c.textRight(Format.currency(line.rate, d.currencySymbol), 372f, base + 20f, cell)
            }
            c.textRight(Format.currency(line.amount, d.currencySymbol), right - 12f, base + 20f, amountPaint)
            c.hLine(MARGIN + 14f, right - 12f, base + ROW_H - 1f, LIGHT, 0.8f)
        }

        // ---- Total bar ----
        c.card(200f, t.total, right, t.total + TOTAL_H, 8f, GOLD)
        c.card(204f, t.total, right, t.total + TOTAL_H, 8f, NAVY)
        c.textLeft("Total Paid", 220f, t.total + 26f, paint(13f, true, WHITE))
        val totalText = Format.currency(d.total, d.currencySymbol)
        val totalPaint = shrink(totalText, paint(19f, true, GOLD), 150f, 11f)
        c.textRight(totalText, right - 14f, t.total + 28f, totalPaint)

        // ---- Payment box ----
        c.card(MARGIN, t.pay, right, t.pay + PAY_H, 14f, MINT, 0xFFCFE6D8.toInt())
        c.badge(MARGIN + 30f, t.pay + 30f, 17f, GREEN, WHITE, Glyph.CHECK)
        c.textLeft("Payment Successful", MARGIN + 56f, t.pay + 35f, paint(14f, true, GREEN))
        val payLabel = paint(9.5f, true, GREEN)
        val payValue = paint(9.5f, false, INK)
        listOf(
            "Paid with" to d.paymentMethod,
            "Payment Date" to receiptDateTime(d.producedAt, zone),
            "Status" to d.paymentStatus
        ).forEachIndexed { i, (label, value) ->
            val base = t.pay + 60f + i * 15f
            c.textLeft(label, MARGIN + 16f, base, payLabel)
            c.textLeft(fit(value, payValue, 170f), MARGIN + 100f, base, payValue)
        }
        c.vLine(330f, t.pay + 14f, t.pay + PAY_H - 14f, 0xFFBBD8C6.toInt(), 1f)
        c.card(352f, t.pay + 8f, 432f, t.pay + 88f, 8f, WHITE)
        c.qr(d.receiptNumber, 358f, t.pay + 14f, 68f)
        c.textCenter("Booking reference", 392f, t.pay + 97f, paint(8f, false, GREEN))

        // ---- Footer: thanks and the hotel's details ----
        c.vLine(MARGIN + 1.5f, t.foot, t.foot + 60f, GOLD, 3f)
        var y = t.foot + 12f
        val thanksPaint = paint(11f, true, NAVY)
        for (line in wrap("Thank you for choosing ${d.businessName}.", thanksPaint, THANKS_W, 2)) {
            c.textLeft(line, MARGIN + 12f, y, thanksPaint)
            y += 14f
        }
        c.textLeft("We look forward to welcoming you again.", MARGIN + 12f, y + 2f, paint(8.5f, false, GREY))
        d.hotel.tagline?.let {
            val handwriting = shrink(it, script(17f, GOLD), THANKS_W, 11f)
            c.textLeft(fit(it, handwriting, THANKS_W), MARGIN + 12f, y + 28f, handwriting)
        }

        if (t.contact.isNotEmpty()) {
            c.vLine(236f, t.foot, t.foot + t.footHeight, LIGHT, 1f)
            val contactPaint = paint(8.5f, false, INK)
            var cy = t.foot + 10f
            for (lines in t.contact) {
                c.dot(CONTACT_X + 3f, cy - 3f)
                for (text in lines) {
                    c.textLeft(text, CONTACT_X + 14f, cy, contactPaint)
                    cy += 12.5f
                }
                cy += 5f
            }
        }

        c.footerBar(t.height - BOTTOM_H, BOTTOM_H, Branding.COPYRIGHT)
    }
}
