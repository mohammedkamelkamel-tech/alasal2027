package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BillEntity
import com.example.data.model.BillStatus
import com.example.data.model.PaymentEntity
import com.example.data.model.UserEntity
import com.example.utils.CurrencyFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class StatementEntry(
    val timestamp: Long,
    val title: String,
    val date: String,
    val amount: Double,
    val detail: String,
    val isPayment: Boolean
)

@Composable
fun SubscriberAccountStatementScreen(
    user: UserEntity?,
    bills: List<BillEntity>,
    payments: List<PaymentEntity>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    if (user == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("تعذر العثور على بيانات المشترك")
            Button(onClick = onBack) { Text("رجوع") }
        }
        return
    }

    val userBills = bills.filter { it.userId == user.id }.sortedByDescending { it.createdAt }
    val userPayments = payments.filter { it.userId == user.id }.sortedByDescending { it.paymentAt }
    // الفواتير المرحّلة لا تدخل في الرصيد؛ متبقيها نُقل إلى فاتورة أحدث.
    val currentBills = userBills.filter { it.status != BillStatus.CARRIED.name }
    val billedConsumption = currentBills.sumOf { it.subtotalAmount }
    val paidReceipts = userPayments.sumOf { it.amount }
    val outstanding = currentBills.sumOf { it.remainingAmount }.coerceAtLeast(0.0)
    val credit = (paidReceipts - currentBills.sumOf { it.totalAmount }).coerceAtLeast(0.0)

    val entries = (userBills.map { bill ->
        StatementEntry(
            timestamp = bill.createdAt,
            title = "فاتورة استهلاك #${bill.invoiceNumber}",
            date = bill.issueDate.ifBlank { bill.readingDate.ifBlank { dateText(bill.createdAt) } },
            amount = bill.totalAmount,
            detail = when (bill.status) {
                BillStatus.CARRIED.name -> "مرحّلة إلى فاتورة أحدث • الاستهلاك ${bill.consumptionKwh} ك.و.س"
                else -> "${bill.consumptionKwh} ك.و.س • مدفوع الفاتورة: ${CurrencyFormatter.riyal(bill.paidAmount)} • المتبقي: ${CurrencyFormatter.riyal(bill.remainingAmount.coerceAtLeast(0.0))}"
            },
            isPayment = false
        )
    } + userPayments.map { payment ->
        StatementEntry(
            timestamp = payment.paymentAt,
            title = "إيصال تحصيل #${payment.receiptNumber.takeIf { it > 0 } ?: "-"}",
            date = payment.paymentDate.ifBlank { dateText(payment.paymentAt) },
            amount = payment.amount,
            detail = "الفاتورة #${payment.invoiceNumber} • ${payment.method} • المحصّل: ${payment.collectorName.ifBlank { "-"} }",
            isPayment = true
        )
    }).sortedByDescending { it.timestamp }

    val shareText = buildString {
        appendLine("كشف حساب المشترك - محطة العسل")
        appendLine("المشترك: ${user.name}")
        appendLine("رقم المشترك: ${user.email.ifBlank { user.userIdCode }}")
        appendLine("الهاتف: ${user.phone}")
        appendLine("--------------------------------")
        appendLine("إجمالي الاستهلاك: ${CurrencyFormatter.riyal(billedConsumption)}")
        appendLine("إجمالي التحصيلات المسجلة: ${CurrencyFormatter.riyal(paidReceipts)}")
        appendLine("الرصيد المستحق الحالي: ${CurrencyFormatter.riyal(outstanding)}")
        if (credit > 0.0) appendLine("رصيد دائن تقريبي: ${CurrencyFormatter.riyal(credit)}")
        appendLine("--------------------------------")
        entries.forEach { entry ->
            appendLine("${entry.date} | ${entry.title} | ${if (entry.isPayment) "مدفوع" else "قيمة الفاتورة"}: ${CurrencyFormatter.riyal(entry.amount)}")
            appendLine(entry.detail)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع") }
            Column(Modifier.weight(1f)) {
                Text("كشف حساب المشترك", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(user.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(intent, "مشاركة كشف الحساب"))
            }) { Icon(Icons.Filled.Share, contentDescription = "مشاركة كشف الحساب") }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryRow("إجمالي فواتير الاستهلاك", CurrencyFormatter.riyal(billedConsumption))
                SummaryRow("إجمالي التحصيلات", CurrencyFormatter.riyal(paidReceipts))
                HorizontalDivider()
                SummaryRow("المتبقي المستحق", CurrencyFormatter.riyal(outstanding), emphasize = true)
                if (credit > 0.0) SummaryRow("رصيد دائن تقريبي", CurrencyFormatter.riyal(credit))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("سجل الفواتير والتحصيلات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد فواتير أو تحصيلات مسجلة لهذا المشترك")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries) { entry ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Text(
                                    (if (entry.isPayment) "+" else "") + CurrencyFormatter.riyal(entry.amount),
                                    color = if (entry.isPayment) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(entry.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (emphasize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

private fun dateText(timestamp: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
