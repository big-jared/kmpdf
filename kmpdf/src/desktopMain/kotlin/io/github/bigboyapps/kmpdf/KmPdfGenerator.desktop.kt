package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createKmPdfGenerator(): KmPdfGenerator = DesktopKmPdfGenerator()

actual fun sharePdf(uri: String, title: String) {
    // Desktop file sharing - to be implemented
    println("Share PDF not yet implemented for Desktop")
}

class DesktopKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        content: @Composable () -> Unit
    ): PdfResult {
        return withContext(Dispatchers.IO) {
            try {
                PdfResult.Error("Composable-to-PDF rendering not yet implemented for Desktop. Coming soon!")
            } catch (e: Exception) {
                PdfResult.Error("Failed to generate PDF", e)
            }
        }
    }
}
