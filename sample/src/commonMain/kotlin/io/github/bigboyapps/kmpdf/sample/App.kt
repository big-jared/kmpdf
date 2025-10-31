package io.github.bigboyapps.kmpdf.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun SampleScreen() {
    val scope = rememberCoroutineScope()
    val generator = remember { createKmPdfGenerator() }
    var pdfUri by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var selectedPageSize by remember { mutableStateOf(PageSize.A4) }
    var selectedMargins by remember { mutableStateOf(PageMargins.Normal) }

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

                Text("Margins")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "None" to PageMargins.None,
                        "Narrow" to PageMargins.Narrow,
                        "Normal" to PageMargins.Normal,
                        "Wide" to PageMargins.Wide
                    ).forEach { (name, margins) ->
                        FilterChip(
                            selected = selectedMargins == margins,
                            onClick = { selectedMargins = margins },
                            label = { Text(name) }
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
                            margins = selectedMargins,
                            fileName = "sample_${System.currentTimeMillis()}.pdf"
                        )
                    ) {
                        SamplePdfContent()
                    }) {
                        is PdfResult.Success -> {
                            pdfUri = result.uri
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
            text = "Introduction",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "This is a sample PDF document generated using KmPDF, a Kotlin Multiplatform library for creating PDFs from Compose UI.",
            style = MaterialTheme.typography.bodyLarge
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
