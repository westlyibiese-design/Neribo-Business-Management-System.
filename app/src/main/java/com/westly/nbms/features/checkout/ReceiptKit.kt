package com.westly.nbms.features.checkout

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.Locale

// ---- Dates (pure, unit-tested) ---------------------------------------------------------------------

private val RECEIPT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private const val RECEIPT_EMPTY = "—"

/** "Nov 3, 2025" in [zone]; "—" when there is no date. */
internal fun receiptDate(i: Instant?, zone: TimeZone): String {
    if (i == null) return RECEIPT_EMPTY
    val d = i.toLocalDateTime(zone)
    return "${RECEIPT_MONTHS[d.date.monthNumber - 1]} ${d.date.dayOfMonth}, ${d.date.year}"
}

/** "2:00 PM" / "11:24 AM" / "12:05 AM" in [zone]; "—" when there is no time. */
internal fun receiptTime(i: Instant?, zone: TimeZone): String {
    if (i == null) return RECEIPT_EMPTY
    val d = i.toLocalDateTime(zone)
    val hour12 = if (d.hour % 12 == 0) 12 else d.hour % 12
    return String.format(Locale.US, "%d:%02d %s", hour12, d.minute, if (d.hour < 12) "AM" else "PM")
}

/** "Nov 3, 2025 • 11:24 AM". */
internal fun receiptDateTime(i: Instant?, zone: TimeZone): String =
    if (i == null) RECEIPT_EMPTY else "${receiptDate(i, zone)} • ${receiptTime(i, zone)}"

/** The hotel's contact details that are filled in, in a fixed order: address, phone, email, website. */
internal fun contactItems(h: ReceiptHotel): List<String> =
    listOfNotNull(h.address, h.phone, h.email, h.website).map { it.trim() }.filter { it.isNotEmpty() }

// ---- Drawing toolkit shared by the check-in and check-out receipts ----------------------------------

/** Navy and gold paints, text fitting, rounded cards, small badges and a vector QR code. */
internal object ReceiptKit {

    const val W = 480f
    const val MARGIN = 28f

    const val NAVY = 0xFF0B1F3A.toInt()
    const val NAVY_DEEP = 0xFF06142B.toInt()
    const val NAVY_LIGHT = 0xFF16345F.toInt()
    const val GOLD = 0xFFC9A24B.toInt()
    const val INK = 0xFF1F2937.toInt()
    const val GREY = 0xFF6B7280.toInt()
    const val LIGHT = 0xFFE5E7EB.toInt()
    const val CARD = 0xFFF4F5F8.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MINT = 0xFFE7F3EC.toInt()
    const val GREEN = 0xFF14633C.toInt()
    const val AMBER_BG = 0xFFFEF3E2.toInt()
    const val AMBER = 0xFF9A5B00.toInt()

    enum class Glyph { PERSON, BED, DOC, CHECK, EXCLAIM }

    fun paint(
        size: Float,
        bold: Boolean,
        color: Int,
        italic: Boolean = false,
        spacing: Float = 0f,
        family: Typeface = Typeface.SANS_SERIF
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(
            family,
            when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
        )
        letterSpacing = if (size > 0f) spacing / size else 0f
    }

    fun serif(size: Float, bold: Boolean, color: Int, spacing: Float = 0f): Paint =
        paint(size, bold, color, spacing = spacing, family = Typeface.SERIF)

    /** The handwritten tagline: the system "cursive" family, or italic sans where a phone has none. */
    fun script(size: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create("cursive", Typeface.NORMAL)
    }

    /** Cuts [text] with "…" so it fits in [maxWidth]. */
    fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    /** Makes [paint] smaller, down to [minSize], until [text] fits in [maxWidth]. */
    fun shrink(text: String, paint: Paint, maxWidth: Float, minSize: Float): Paint {
        while (paint.textSize > minSize && paint.measureText(text) > maxWidth) paint.textSize -= 1f
        return paint
    }

    /** Breaks [text] into at most [maxLines] lines no wider than [maxWidth]; the last line gets "…" when text is left. */
    fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || maxLines < 1) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                lines += current
                if (lines.size == maxLines) {
                    lines[maxLines - 1] = lines[maxLines - 1] + "…"
                    return lines.map { fit(it, paint, maxWidth) }
                }
                current = word
            }
        }
        lines += current
        return lines.map { fit(it, paint, maxWidth) }
    }

    fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    fun stroke(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    fun Canvas.card(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, border: Int? = null) {
        drawRoundRect(RectF(l, t, r, b), radius, radius, fill(color))
        if (border != null) drawRoundRect(RectF(l, t, r, b), radius, radius, stroke(border, 0.8f))
    }

    fun Canvas.hLine(x1: Float, x2: Float, y: Float, color: Int, width: Float) {
        drawLine(x1, y, x2, y, stroke(color, width))
    }

    fun Canvas.vLine(x: Float, y1: Float, y2: Float, color: Int, width: Float) {
        drawLine(x, y1, x, y2, stroke(color, width))
    }

    fun Canvas.textLeft(text: String, x: Float, baseline: Float, paint: Paint) {
        drawText(text, x, baseline, paint)
    }

    fun Canvas.textRight(text: String, rightX: Float, baseline: Float, paint: Paint) {
        drawText(text, rightX - paint.measureText(text), baseline, paint)
    }

    fun Canvas.textCenter(text: String, centerX: Float, baseline: Float, paint: Paint) {
        drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint)
    }

    /** The dark header: a navy gradient, a thin gold diagonal and the gold rule along the bottom edge. */
    fun Canvas.header(height: Float) {
        val gradient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, W, height, NAVY_DEEP, NAVY_LIGHT, Shader.TileMode.CLAMP)
        }
        drawRect(0f, 0f, W, height, gradient)
        drawLine(W * 0.62f, 0f, W * 0.54f, height, stroke(GOLD, 1.4f))
        drawRect(0f, height - 2.5f, W, height, fill(GOLD))
    }

    /** The hotel name as a spaced serif wordmark with the tagline under it, centred on [centerX]. */
    fun Canvas.wordmark(name: String, tagline: String?, centerX: Float, maxWidth: Float, baseline: Float) {
        val title = name.trim().uppercase(Locale.getDefault())
        val p = shrink(title, serif(25f, false, WHITE, spacing = 3.2f), maxWidth, 11f)
        textCenter(fit(title, p, maxWidth), centerX, baseline, p)
        if (!tagline.isNullOrBlank()) {
            val t = tagline.trim().uppercase(Locale.getDefault())
            val tp = shrink(t, paint(8f, false, GOLD, spacing = 2.4f), maxWidth, 6f)
            textCenter(fit(t, tp, maxWidth), centerX, baseline + 22f, tp)
        }
    }

    /** A round badge with a simple drawn symbol inside. */
    fun Canvas.badge(cx: Float, cy: Float, r: Float, bg: Int, fg: Int, glyph: Glyph) {
        drawCircle(cx, cy, r, fill(bg))
        val s = stroke(fg, r * 0.1f)
        when (glyph) {
            Glyph.PERSON -> {
                drawCircle(cx, cy - r * 0.22f, r * 0.2f, s)
                drawArc(RectF(cx - r * 0.42f, cy + r * 0.02f, cx + r * 0.42f, cy + r * 0.78f), 180f, 180f, false, s)
            }
            Glyph.BED -> {
                drawRoundRect(RectF(cx - r * 0.5f, cy - r * 0.02f, cx + r * 0.5f, cy + r * 0.32f), r * 0.06f, r * 0.06f, s)
                drawLine(cx - r * 0.5f, cy - r * 0.34f, cx - r * 0.5f, cy + r * 0.32f, s)
                drawRoundRect(RectF(cx - r * 0.34f, cy - r * 0.2f, cx - r * 0.06f, cy - r * 0.02f), r * 0.05f, r * 0.05f, s)
            }
            Glyph.DOC -> {
                drawRoundRect(RectF(cx - r * 0.3f, cy - r * 0.42f, cx + r * 0.3f, cy + r * 0.42f), r * 0.08f, r * 0.08f, s)
                drawLine(cx - r * 0.16f, cy - r * 0.14f, cx + r * 0.16f, cy - r * 0.14f, s)
                drawLine(cx - r * 0.16f, cy + r * 0.04f, cx + r * 0.16f, cy + r * 0.04f, s)
                drawLine(cx - r * 0.16f, cy + r * 0.22f, cx + r * 0.04f, cy + r * 0.22f, s)
            }
            Glyph.CHECK -> {
                val path = Path().apply {
                    moveTo(cx - r * 0.36f, cy + r * 0.02f)
                    lineTo(cx - r * 0.1f, cy + r * 0.3f)
                    lineTo(cx + r * 0.38f, cy - r * 0.26f)
                }
                drawPath(path, stroke(fg, r * 0.16f))
            }
            Glyph.EXCLAIM -> {
                val p = paint(r * 1.3f, true, fg)
                textCenter("!", cx, cy + r * 0.45f, p)
            }
        }
    }

    /** A small gold dot used as the bullet in contact lists. */
    fun Canvas.dot(x: Float, y: Float) {
        drawCircle(x, y, 2.2f, fill(GOLD))
    }

    /**
     * Draws [content] as a QR code, square, with its top-left at ([left], [top]) and side [size], as vector squares.
     * Nothing is drawn if the text cannot be encoded.
     */
    fun Canvas.qr(content: String, left: Float, top: Float, size: Float) {
        try {
            val matrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0)
            )
            val cell = size / matrix.width
            val ink = fill(NAVY_DEEP)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    if (matrix.get(x, y)) {
                        drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell + 0.3f, top + (y + 1) * cell + 0.3f, ink)
                    }
                }
            }
        } catch (e: Exception) {
            // A receipt without a QR code is still a valid receipt.
        }
    }

    /** Bottom bar with the copyright line. */
    fun Canvas.footerBar(top: Float, height: Float, text: String) {
        drawRect(0f, top, W, top + height, fill(NAVY_DEEP))
        drawRect(0f, top, W, top + 2f, fill(GOLD))
        textCenter(text, W / 2f, top + height / 2f + 4f, paint(10f, true, GOLD, spacing = 0.4f))
    }
}
