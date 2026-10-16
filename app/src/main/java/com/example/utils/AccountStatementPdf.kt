package com.example.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

object AccountStatementPdf {
    fun create(context: Context, title: String, lines: List<String>): File {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas: Canvas = page.canvas
        var y = 48f
        canvas.drawText(title, 36f, y, paint)
        y += 28f
        lines.forEach { line ->
            if (y < 800f) {
                canvas.drawText(line.take(90), 36f, y, paint)
                y += 20f
            }
        }
        pdf.finishPage(page)
        val folder = File(context.cacheDir, "statements")
        folder.mkdirs()
        val file = File(folder, "account_statement.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }
}
