package com.westly.nbms.features.reports

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

/** Thin renderer: draws a [PdfLayout] with [PdfDocument] (A4 portrait) and returns the PDF bytes. All positions come from the layout. */
internal object FinancialReportPdf {

    /** Builds the layout of [report] and draws it. */
    fun create(report: FinancialReport, generatedAt: String, generatedBy: String): ByteArray =
        render(FinancialReportPdfLayout.build(report, generatedAt, generatedBy))

    fun render(layout: PdfLayout): ByteArray {
        val document = PdfDocument()
        try {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val regular = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            layout.pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(layout.width, layout.height, index + 1).create()
                val pdfPage = document.startPage(info)
                val canvas = pdfPage.canvas
                page.elements.forEach { element ->
                    when (element) {
                        is PdfText -> {
                            textPaint.typeface = if (element.bold) bold else regular
                            textPaint.textSize = element.size
                            textPaint.color = element.color
                            textPaint.textAlign = if (element.align == PdfAlign.RIGHT) Paint.Align.RIGHT else Paint.Align.LEFT
                            canvas.drawText(element.text, element.x, element.y, textPaint)
                        }
                        is PdfLine -> {
                            linePaint.strokeWidth = element.width
                            linePaint.color = element.color
                            canvas.drawLine(element.x1, element.y1, element.x2, element.y2, linePaint)
                        }
                    }
                }
                document.finishPage(pdfPage)
            }

            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }
}
