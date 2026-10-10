package com.westly.nbms.features.checkout

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.westly.nbms.core.util.Format
import java.io.OutputStream
import com.westly.nbms.core.util.Branding

/**
 * Draws the guest receipt as an A5 portrait PDF with [PdfDocument]. Navy `#0B1F3A` and gold `#C9A24B`, system
 * sans-serif, 10–12 point text. A long list of extensions simply continues on a second page.
 */
internal object ReceiptPdfBuilder {

    private const val PAGE_WIDTH = 420
    private const val PAGE_HEIGHT = 595
    private const val MARGIN = 32f
    private const val TOP = 48f
    private const val BOTTOM = 40f
    private const val VALUE_X = 140f

    private const val NAVY = 0xFF0B1F3A.toInt()
    private const val GOLD = 0xFFC9A24B.toInt()
    private const val INK = 0xFF1F2937.toInt()
    private const val GREY = 0xFF6B7280.toInt()
    private const val LIGHT = 0xFFD1D5DB.toInt()

    /** Writes the PDF to [out]. The caller closes the stream. */
    fun build(data: ReceiptData, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val sheet = Sheet(doc)
            sheet.newPage()
            draw(sheet, data)
            sheet.finish()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun draw(s: Sheet, d: ReceiptData) {
        val right = PAGE_WIDTH - MARGIN
        val full = PAGE_WIDTH - 2 * MARGIN

        // Business name and the gold rule
        s.text(fit(d.businessName, paint(16f, true, NAVY), full), MARGIN, paint(16f, true, NAVY))
        s.y += 8f
        s.line(MARGIN, right, GOLD, 2f)
        s.y += 20f

        s.text("GUEST RECEIPT", MARGIN, paint(12f, true, NAVY, spacing = 1.5f))
        s.y += 6f
        s.text("Receipt No: ${d.receiptNumber}", MARGIN, paint(10f, false, GREY))
        s.text("Issued: ${Format.dateTime(d.producedAt, d.zone)}", MARGIN, paint(10f, false, GREY))
        s.y += 10f

        // Guest and stay
        s.row("Guest", d.guestName)
        d.guestPhone?.let { s.row("Phone", it) }
        s.row("Room", d.roomNumber)
        d.roomType?.let { s.row("Room type", it) }
        s.row("Check-in", Format.dateTime(d.checkIn, d.zone))
        s.row("Check-out", Format.dateTime(d.checkOut, d.zone))
        d.nights?.let { s.row("Nights", it.toString()) }
        s.y += 10f

        // Charges
        s.ensure(40f)
        val head = paint(10f, true, GREY)
        s.textRight("AMOUNT", right, head, advance = false)
        s.text("DESCRIPTION", MARGIN, head)
        s.y += 2f
        s.line(MARGIN, right, LIGHT, 1f)
        s.y += 12f

        val body = paint(11f, false, INK)
        val note = paint(9f, false, GREY)
        for (line in d.lines) {
            s.ensure(if (line.note != null) 30f else 18f)
            val amountText = Format.currency(line.amount, d.currencySymbol)
            s.textRight(amountText, right, body, advance = false)
            s.text(fit(line.label, body, full - 90f), MARGIN, body)
            if (line.note != null) {
                s.text(fit(line.note, note, full - 90f), MARGIN, note)
            }
            s.y += 4f
        }
        s.y += 2f
        s.line(MARGIN, right, NAVY, 1f)
        s.y += 14f

        s.ensure(30f)
        val total = paint(12f, true, NAVY)
        s.textRight(Format.currency(d.total, d.currencySymbol), right, total, advance = false)
        s.text("Total", MARGIN, total)
        s.y += 10f

        s.row("Payment method", d.paymentMethod)
        s.row("Status", d.paymentStatus, valuePaint = paint(11f, true, NAVY))
        s.y += 20f

        s.ensure(30f)
        s.textCentered("Thank you for staying with us.", PAGE_WIDTH / 2f, paint(10f, false, GREY, italic = true))
    }

    // ---- Paint and text fitting ----

    private fun paint(size: Float, bold: Boolean, color: Int, italic: Boolean = false, spacing: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
            )
            letterSpacing = spacing / size
        }

    /** Cuts [text] with "…" so it fits in [maxWidth]. */
    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    /** The page being drawn, with a moving line position and page breaks. */
    private class Sheet(private val doc: PdfDocument) {
        private var number = 0
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        var y = TOP

        fun newPage() {
            page?.let {
                drawCopyright()
                doc.finishPage(it)
            }
            number++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()
            val p = doc.startPage(info)
            page = p
            canvas = p.canvas
            y = TOP
        }

        fun finish() {
            page?.let {
                drawCopyright()
                doc.finishPage(it)
            }
            page = null
        }

        /** The copyright line, centred at the bottom of every page. */
        private fun drawCopyright() {
            textCentered(Branding.COPYRIGHT, PAGE_WIDTH / 2f, paint(9f, false, GREY), baseline = PAGE_HEIGHT - 18f)
        }

        fun ensure(height: Float) {
            if (y + height > PAGE_HEIGHT - BOTTOM) newPage()
        }

        /** Draws one line of text at the current position and moves down. */
        fun text(text: String, x: Float, paint: Paint) {
            ensure(paint.textSize + 6f)
            y += paint.textSize
            canvas?.drawText(text, x, y, paint)
            y += 5f
        }

        /** Draws right-aligned text; with [advance] false the position stays so a left label can share the line. */
        fun textRight(text: String, rightX: Float, paint: Paint, advance: Boolean) {
            val baseline = y + paint.textSize
            canvas?.drawText(text, rightX - paint.measureText(text), baseline, paint)
            if (advance) y = baseline + 5f
        }

        fun textCentered(text: String, centerX: Float, paint: Paint, baseline: Float? = null) {
            if (baseline != null) {
                canvas?.drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint)
                return
            }
            y += paint.textSize
            canvas?.drawText(text, centerX - paint.measureText(text) / 2f, y, paint)
            y += 5f
        }

        fun line(fromX: Float, toX: Float, color: Int, width: Float) {
            val p = Paint().apply {
                this.color = color
                strokeWidth = width
            }
            canvas?.drawLine(fromX, y, toX, y, p)
        }

        /** A grey label on the left and a value that starts at a fixed column. */
        fun row(label: String, value: String, valuePaint: Paint = paint(11f, false, INK)) {
            ensure(valuePaint.textSize + 8f)
            val labelPaint = paint(10f, false, GREY)
            y += valuePaint.textSize
            canvas?.drawText(label, MARGIN, y, labelPaint)
            canvas?.drawText(fit(value, valuePaint, PAGE_WIDTH - MARGIN - VALUE_X), VALUE_X, y, valuePaint)
            y += 5f
        }
    }
}
