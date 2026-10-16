package com.example.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AccountStatementPdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 38f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    fun create(context: Context, title: String, lines: List<String>): File {
        val document = PdfDocument()
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 65)
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 91, 160)
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subheadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(65, 75, 88)
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(215, 222, 230)
            strokeWidth = 1f
        }
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun startPage() {
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page!!.canvas
            canvas!!.drawColor(Color.WHITE)
            y = 38f
            canvas!!.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 4f, headingPaint)
            y += 22f
            canvas!!.drawText("محطة العسل لفواتير الكهرباء", MARGIN, y, subheadingPaint)
            canvas!!.drawText("صفحة $pageNumber", PAGE_WIDTH - MARGIN - 48f, y, bodyPaint)
            y += 25f
            canvas!!.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 24f
        }

        fun finishPage() {
            page?.let { document.finishPage(it) }
            page = null
            canvas = null
        }

        fun drawParagraph(text: String, paint: TextPaint, gapAfter: Float = 7f) {
            val availableWidth = CONTENT_WIDTH.toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .build()
            val requiredHeight = layout.height.toFloat()
            if (y + requiredHeight + gapAfter > PAGE_HEIGHT - 42f) {
                finishPage()
                startPage()
            }
            canvas!!.save()
            canvas!!.translate(MARGIN, y)
            layout.draw(canvas!!)
            canvas!!.restore()
            y += requiredHeight + gapAfter
        }

        try {
            startPage()
            drawParagraph(title, TextPaint(headingPaint).apply { textSize = 18f }, 10f)
            drawParagraph("تاريخ إصدار الكشف: " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()), bodyPaint, 12f)
            canvas!!.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 14f

            var detailsSection = false
            lines.forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    y += 3f
                } else if (
                    line.contains("تفاصيل الفواتير والتحصيلات") ||
                    line.contains("بيانات المشترك") ||
                    line.contains("ملخص الحساب")
                ) {
                    if (y + 28f > PAGE_HEIGHT - 42f) {
                        finishPage()
                        startPage()
                    }
                    y += 5f
                    canvas!!.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 23f, 4f, 4f, backgroundPaint.apply { color = Color.rgb(232, 241, 250) })
                    drawParagraph(line, TextPaint(subheadingPaint).apply { color = Color.rgb(24, 91, 160) }, 8f)
                    detailsSection = line.contains("تفاصيل")
                } else {
                    drawParagraph(line, bodyPaint, if (detailsSection) 9f else 5f)
                    if (detailsSection && index < lines.lastIndex) {
                        canvas!!.drawLine(MARGIN, y - 3f, PAGE_WIDTH - MARGIN, y - 3f, linePaint)
                    }
                }
            }
            finishPage()
            val folder = File(context.cacheDir, "statements")
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "account_statement.pdf")
            FileOutputStream(file).use { document.writeTo(it) }
            return file
        } finally {
            document.close()
        }
    }
}
