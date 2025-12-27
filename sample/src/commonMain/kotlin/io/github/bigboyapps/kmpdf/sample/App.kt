package io.github.bigboyapps.kmpdf.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bigboyapps.kmpdf.*
import kotlinx.coroutines.launch

@Composable
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            SampleScreen()
        }
    }
}

enum class SampleType {
    DEFAULT, LONG_TABLE, MIXED_CONTENT
}

@Composable
fun SampleScreen() {
    val scope = rememberCoroutineScope()
    val generator = remember { createKmPdfGenerator() }
    var pdfUri by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var selectedPageSize by remember { mutableStateOf(PageSize.A4) }
    var selectedSample by remember { mutableStateOf(SampleType.DEFAULT) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "KmPDF Sample App",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Generate PDF from Compose UI",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text("Page Size")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "A4" to PageSize.A4,
                        "Letter" to PageSize.Letter,
                        "Legal" to PageSize.Legal
                    ).forEach { (name, size) ->
                        FilterChip(
                            selected = selectedPageSize == size,
                            onClick = { selectedPageSize = size },
                            label = { Text(name) }
                        )
                    }
                }

                Text("Sample Type")
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Default (Chart & Text)" to SampleType.DEFAULT,
                        "Long Table (50+ rows)" to SampleType.LONG_TABLE,
                        "Mixed Content (Text, Lists, Quotes)" to SampleType.MIXED_CONTENT
                    ).forEach { (name, type) ->
                        FilterChip(
                            selected = selectedSample == type,
                            onClick = { selectedSample = type },
                            label = { Text(name) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    isGenerating = true
                    errorMessage = null
                    pdfUri = null

                    when (val result = generator.generatePdf(
                        config = PdfConfig(
                            pageSize = selectedPageSize,
                            fileName = "sample_${getCurrentTimestamp()}.pdf"
                        )
                    ) {
                        when (selectedSample) {
                            SampleType.DEFAULT -> {
                                page {
                                    SamplePdfContent()
                                }
                            }
                            SampleType.LONG_TABLE -> {
                                // Create 3 pages with table content
                                repeat(3) { pageIndex ->
                                    page {
                                        LongTablePage(pageIndex + 1)
                                    }
                                }
                            }
                            SampleType.MIXED_CONTENT -> {
                                page {
                                    MixedContentPage(1)
                                }
                                page {
                                    MixedContentPage(2)
                                }
                            }
                        }
                    }) {
                        is PdfResult.Success -> {
                            pdfUri = "${result.uri}\nPages: ${result.pageCount}, Size: ${result.fileSize / 1024}KB"
                            // Automatically open share sheet
                            sharePdf(result.uri, "Share PDF")
                        }
                        is PdfResult.Error -> {
                            errorMessage = result.message
                        }
                    }
                    isGenerating = false
                }
            },
            enabled = !isGenerating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isGenerating) "Generating PDF..." else "Generate PDF")
        }

        pdfUri?.let { uri ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "✓ PDF Generated Successfully!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Path: $uri",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Preview of PDF Content:",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            SamplePdfContent()
        }
    }
}

@Composable
fun SamplePdfContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Sample Document",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Generated with KmPDF",
            style = MaterialTheme.typography.titleMedium,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Monthly Revenue",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        SimpleBarChart(
            data = listOf(
                "Jan" to 45f,
                "Feb" to 62f,
                "Mar" to 58f,
                "Apr" to 73f,
                "May" to 85f,
                "Jun" to 91f
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Features",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        listOf(
            "Cross-platform support (Android, iOS, Desktop, WASM)",
            "Render any @Composable to PDF",
            "Configurable page sizes and margins",
            "Simple, intuitive API"
        ).forEach { feature ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("•", style = MaterialTheme.typography.bodyLarge)
                Text(feature, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Conclusion",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "KmPDF makes it easy to generate professional PDF documents from your Compose UI code.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.weight(1f))

        HorizontalDivider()

        Text(
            text = "Generated on ${getCurrentTimestamp()}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

expect fun getCurrentTimestamp(): String

@Composable
fun SimpleBarChart(
    data: List<Pair<String, Float>>,
    modifier: Modifier = Modifier
) {
    val maxValue = data.maxOfOrNull { it.second } ?: 100f

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEach { (label, value) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = "${value.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((value / maxValue * 140).dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            data.forEach { (label, _) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun LongTableSample() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Q3 2024 Sales Report",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Product Performance Overview",
            style = MaterialTheme.typography.titleMedium,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Table header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Product",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(2f)
            )
            Text(
                text = "Units",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
            Text(
                text = "Revenue",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }

        HorizontalDivider()

        // Generate 50+ rows of sample data
        (1..55).forEach { i ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Product ${i}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(2f)
                )
                Text(
                    text = "${(i * 47) % 500}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
                Text(
                    text = "$${(i * 123) % 5000}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }

            if (i % 10 == 0) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider()

        Text(
            text = "Generated on ${getCurrentTimestamp()}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
fun MixedContentSample() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Comprehensive Report",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "A demonstration of various content types in PDF",
            style = MaterialTheme.typography.titleMedium,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Section 1: Executive Summary
        Text(
            text = "Executive Summary",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "This comprehensive report showcases the versatility of KmPDF in handling various content types. " +
                    "From structured data tables to formatted text blocks, the library provides seamless PDF generation " +
                    "capabilities across all supported platforms.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section 2: Key Features with detailed list
        Text(
            text = "Key Features",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        listOf(
            "Cross-Platform Support" to "Works seamlessly on Android, iOS, Desktop (JVM), and WASM platforms",
            "Flexible Layouts" to "Support for complex layouts including columns, rows, and nested structures",
            "Typography" to "Full Material Design typography system with multiple text styles",
            "Custom Styling" to "Apply colors, backgrounds, shapes, and other styling options",
            "Multi-Page Documents" to "Create documents with multiple pages using simple page { } blocks"
        ).forEach { (title, description) ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 3: Quote/Callout
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "\"KmPDF makes it incredibly easy to generate professional PDF documents directly from " +
                            "your Compose UI code. No more wrestling with low-level PDF APIs!\"",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "— Development Team",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 4: Technical Details Table
        Text(
            text = "Technical Specifications",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            listOf(
                "Rendering Engine" to "Compose Multiplatform",
                "PDF Generation" to "Platform Native APIs",
                "Image Support" to "Skia (iOS/Desktop), Android Graphics",
                "Page Sizes" to "A3, A4, A5, Letter, Legal, Tabloid",
                "Margins" to "None, Narrow, Normal, Wide (customizable)",
                "Output Format" to "PDF 1.4+ compatible"
            ).forEach { (spec, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = spec,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1.5f)
                    )
                }
                HorizontalDivider()
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 5: Code Example
        Text(
            text = "Usage Example",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF5F5F5)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = """
                        val generator = createKmPdfGenerator()

                        generator.generatePdf(
                            config = PdfConfig(
                                pageSize = PageSize.A4,
                                fileName = "my-document.pdf"
                            )
                        ) {
                            page {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Hello PDF!")
                                }
                            }
                        }
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = Color.DarkGray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 6: Conclusion
        Text(
            text = "Conclusion",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "KmPDF represents a modern approach to PDF generation in Kotlin Multiplatform applications. " +
                    "By leveraging Compose UI, developers can create complex PDF documents using familiar declarative " +
                    "patterns, eliminating the complexity traditionally associated with PDF generation.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider()

        Text(
            text = "Generated on ${getCurrentTimestamp()}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
fun LongTablePage(pageNumber: Int, rowsPerPage: Int = 20) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Data Table - Page $pageNumber",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Table header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("ID", modifier = Modifier.weight(0.5f), fontWeight = FontWeight.Bold)
                Text("Name", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("Value", modifier = Modifier.weight(0.7f), fontWeight = FontWeight.Bold)
                Text("Status", modifier = Modifier.weight(0.8f), fontWeight = FontWeight.Bold)
            }

            HorizontalDivider()

            // Table rows - configurable rows per page
            val startRow = (pageNumber - 1) * rowsPerPage + 1
            repeat(rowsPerPage) { index ->
                val rowNumber = startRow + index
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("$rowNumber", modifier = Modifier.weight(0.5f))
                    Text("Item $rowNumber", modifier = Modifier.weight(1f))
                    Text("$${rowNumber * 100}", modifier = Modifier.weight(0.7f))
                    Text(
                        if (rowNumber % 3 == 0) "Complete" else "Pending",
                        modifier = Modifier.weight(0.8f),
                        color = if (rowNumber % 3 == 0) Color(0xFF4CAF50) else Color(0xFFFFA726)
                    )
                }
                if (index < rowsPerPage - 1) {
                    HorizontalDivider()
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Page $pageNumber of 3 • ${getCurrentTimestamp()}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun MixedContentPage(pageNumber: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (pageNumber == 1) {
                Text(
                    text = "Mixed Content Document",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Chapter 1: Introduction",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "This is a sample document demonstrating mixed content across multiple pages.",
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Key Features:",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                listOf(
                    "Multi-page support",
                    "Custom layouts per page",
                    "Tables and charts",
                    "Mixed typography"
                ).forEach { feature ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("•", style = MaterialTheme.typography.bodyLarge)
                        Text(feature, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                Text(
                    text = "Chapter 2: Details",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "This page demonstrates continued content on a second page.",
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE3F2FD)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Important Note",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Each page is rendered independently at the exact page size you specify.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Page $pageNumber of 2",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
