package com.example.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.model.BillEntity
import java.io.File

/**
 * Creates a collector handout from the exact outstanding bill values already
 * exposed to CollectionScreen. This class is read-only: it never writes to Room.
 */
object CollectionDebtPdf {

    fun share(context: Context, pendingBills: List<BillEntity>) {
        require(pendingBills.isNotEmpty()) { "لا توجد مبالغ مستحقة للتحصيل" }

        // Match CollectionScreen's outstanding amount fallback exactly.
        val grouped = pendingBills
            .filter { it.status != "PAID" && it.status != "CARRIED" }
            .groupBy { bill ->
                bill.userId.takeIf { it.isNotBlank() }
                    ?: bill.userName.trim().lowercase()
            }
            .values
            .map { subscriberBills ->
                val first = subscriberBills.maxByOrNull { it.createdAt }!!
                val amount = subscriberBills.sumOf { bill ->
                    bill.remainingAmount.takeIf { it > 0.0 } ?: bill.totalAmount
                }
                CollectorRow(
                    name = first.userName.ifBlank { "مشترك" },
                    phone = subscriberBills.firstNotNullOfOrNull { it.userPhone.takeIf(String::isNotBlank) }.orEmpty(),
                    invoiceNumbers = subscriberBills.map { it.invoiceNumber }.filter(String::isNotBlank).distinct(),
                    amount = amount
                )
            }
            .filter { it.amount > 0.0 }
            .sortedBy { it.name }

        require(grouped.isNotEmpty()) { "لا توجد مبالغ مستحقة للتحصيل" }

        val total = grouped.sumOf { it.amount }
        val lines = buildList {
            add("كشف المبالغ المطلوب تحصيلها")
            add("عدد المشتركين: ${grouped.size}")
            add("إجمالي المبالغ المطلوب تحصيلها: ${CurrencyFormatter.riyal(total)}")
            add("")
            add("تفاصيل المشتركين")
            grouped.forEachIndexed { index, row ->
                add("${index + 1}. ${row.name}")
                if (row.phone.isNotBlank()) add("الهاتف: ${row.phone}")
                if (row.invoiceNumbers.isNotEmpty()) add("أرقام الفواتير: ${row.invoiceNumbers.joinToString("، ")}")
                add("المبلغ المطلوب: ${CurrencyFormatter.riyal(row.amount)}")
                add("")
            }
            add("الإجمالي النهائي: ${CurrencyFormatter.riyal(total)}")
        }

        val generated = AccountStatementPdf.create(
            context = context,
            title = "كشف التحصيل للعامل",
            lines = lines
        )
        // Keep this report separate from subscriber account-statement PDFs.
        val folder = File(context.cacheDir, "statements")
        if (!folder.exists()) folder.mkdirs()
        val reportFile = File(folder, "collection_debts_${System.currentTimeMillis()}.pdf")
        generated.copyTo(reportFile, overwrite = true)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", reportFile)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "إرسال كشف التحصيل PDF"))
    }

    private data class CollectorRow(
        val name: String,
        val phone: String,
        val invoiceNumbers: List<String>,
        val amount: Double
    )
}
