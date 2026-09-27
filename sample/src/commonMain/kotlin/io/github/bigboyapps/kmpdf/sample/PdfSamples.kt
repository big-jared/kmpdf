package io.github.bigboyapps.kmpdf.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bigboyapps.kmpdf.PdfPageInfo

/** A receipt line: description and price in cents. */
data class ReceiptLine(val description: String, val priceCents: Int)

val sampleReceiptLines = listOf(
    ReceiptLine("Flat white", 450),
    ReceiptLine("Almond croissant", 395),
    ReceiptLine("Sparkling water", 250),
    ReceiptLine("Banana bread", 375),
    ReceiptLine("Oat milk upgrade", 75)
)

/** A receipt meant for a page sized to its content with PageSize.wrapHeight. */
@Composable
fun ReceiptContent(lines: List<ReceiptLine> = sampleReceiptLines) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "KmPDF Coffee",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Receipt sized to its content",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        HorizontalDivider()
        lines.forEach { line ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(line.description, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                Text(formatCents(line.priceCents), fontFamily = FontFamily.Monospace)
            }
        }
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Total", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text(formatCents(lines.sumOf { it.priceCents }), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Thanks for visiting!",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** One row of the paginated invoice sample. */
data class InvoiceRow(val number: Int, val item: String, val quantity: Int, val unitPriceCents: Int)

val sampleInvoiceRows = List(100) { index ->
    InvoiceRow(
        number = index + 1,
        item = listOf("Consulting hours", "Design review", "Support plan", "Hosting", "Training session")[index % 5],
        quantity = 1 + index % 4,
        unitPriceCents = 2_500 + index * 125
    )
}

/** Repeated at the top of every invoice page. */
@Composable
fun InvoiceHeader() {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Invoice #2026-0042", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Items flow across pages automatically", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFEDE7F6)).padding(vertical = 4.dp)) {
            Text("#", modifier = Modifier.weight(0.4f), fontWeight = FontWeight.Bold)
            Text("Item", modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold)
            Text("Qty", modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            Text("Amount", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
    }
}

/** One invoice line. */
@Composable
fun InvoiceRowContent(row: InvoiceRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text("${row.number}", modifier = Modifier.weight(0.4f))
            Text(row.item, modifier = Modifier.weight(2f))
            Text("${row.quantity}", modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
            Text(formatCents(row.quantity * row.unitPriceCents), modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
        }
        HorizontalDivider(color = Color(0xFFE0E0E0))
    }
}

/** Repeated at the bottom of every invoice page. */
@Composable
fun InvoiceFooter(info: PdfPageInfo) {
    Text(
        text = "Page ${info.pageNumber} of ${info.pageCount}",
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray
    )
}

private fun formatCents(cents: Int): String = "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
