package com.example.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.model.BillEntity
import java.io.File

/**
 * Read-only collector report. It consumes the same bill snapshot supplied to
 * the app UI and never changes Room data or payment calculations.
 */
object CollectorReportPdf {

    data class Row(
        val subscriberName: String,
        val phone: String,
        val invoiceNumbers: List<String>,
        val amount: Double
    )

    fun buildRows(bills: List<BillEntity>): List<Row> =
        bills.asSequence()
            .filter { it.status != "PAID" && it.status != "CARRIED" }
            .groupBy { bill ->
                bill.userId.takeIf(String::isNotBlank)
                    ?: bill.userName.trim().lowercase()
            }
            .values
            .mapNotNull { subscriberBills ->
                val first = subscriberBills.maxByOrNull { it.createdAt } ?: return@mapNotNull null
                // Match the amount fallback already used by CollectionScreen.
                val amount = subscriberBills.sumOf { bill ->
                    bill.remainingAmount.takeIf { it > 0.0 } ?: bill.totalAmount
                }
                if (amount <= 0.0) return@mapNotNull null
                Row(
                    subscriberName = first.userName.ifBlank { "مشترك" },
                    phone = subscriberBills.firstOrNull { it.userPhone.isNotBlank() }?.userPhone.orEmpty(),
                    invoiceNumbers = subscriberBills.map { it.invoiceNumber }
                        .filter(String::isNotBlank).distinct(),
                    amount = amount
                )
            }
            .sortedBy { it.subscriberName }

    fun createAndShare(context: Context, bills: List<BillEntity>) {
        val rows = buildRows(bills)
        require(rows.isNotEmpty()) { "لا توجد مبالغ مستحقة للتحصيل" }

        val total = rows.sumOf { it.amount }
        val lines = buildList {
            add("عدد المشتركين: ${rows.size}")
            add("إجمالي المبالغ المطلوب تحصيلها: ${CurrencyFormatter.riyal(total)}")
            add("")
            add("تفاصيل المشتركين")
            rows.forEachIndexed { index, row ->
                add("${index + 1}. ${row.subscriberName}")
                if (row.phone.isNotBlank()) add("الهاتف: ${row.phone}")
                if (row.invoiceNumbers.isNotEmpty()) {
                    add("الفواتير: ${row.invoiceNumbers.joinToString("، ")}")
                }
                add("المبلغ المطلوب: ${CurrencyFormatter.riyal(row.amount)}")
                add("")
            }
            add("الإجمالي النهائي: ${CurrencyFormatter.riyal(total)}")
        }

        val generated = AccountStatementPdf.create(
            context = context,
            title = "كشف تحصيل العامل",
            lines = lines
        )
        val folder = File(context.cacheDir, "collector_reports").apply { mkdirs() }
        val report = File(folder, "collector_report_${System.currentTimeMillis()}.pdf")
        generated.copyTo(report, overwrite = true)

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", report
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "إرسال كشف التحصيل"))
    }
}
