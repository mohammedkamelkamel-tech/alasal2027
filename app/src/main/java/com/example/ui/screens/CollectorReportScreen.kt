package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BillEntity
import com.example.utils.CollectorReportPdf
import com.example.utils.CurrencyFormatter

@Composable
fun CollectorReportScreen(bills: List<BillEntity>) {
    val context = LocalContext.current
    val rows = CollectorReportPdf.buildRows(bills)
    val total = rows.sumOf { it.amount }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "كشف تحصيل العامل",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                "كشف مستقل للديون المتبقية، دون تعديل الفواتير أو عمليات التحصيل.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("عدد المشتركين: ${rows.size}")
                    Text(
                        "إجمالي المطلوب: ${CurrencyFormatter.riyal(total)}",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    try {
                        CollectorReportPdf.createAndShare(context, bills)
                    } catch (error: Exception) {
                        Toast.makeText(
                            context,
                            error.message?.takeIf { it.isNotBlank() } ?: "تعذر إنشاء ملف PDF",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = rows.isNotEmpty()
            ) {
                androidx.compose.material3.Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("إنشاء ومشاركة كشف PDF")
            }
        }
        if (rows.isEmpty()) {
            item { Text("لا توجد مبالغ مستحقة للتحصيل حالياً.") }
        } else {
            items(rows, key = { it.subscriberName + it.phone }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                row.subscriberName,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                CurrencyFormatter.riyal(row.amount),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (row.phone.isNotBlank()) {
                            Text("الهاتف: ${row.phone}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (row.invoiceNumbers.isNotEmpty()) {
                            Text(
                                "الفواتير: ${row.invoiceNumbers.joinToString("، ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
