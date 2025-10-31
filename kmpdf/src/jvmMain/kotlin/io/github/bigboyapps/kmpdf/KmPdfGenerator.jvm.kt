package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createKmPdfGenerator(): KmPdfGenerator = JvmKmPdfGenerator()

class JvmKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        content: @Composable () -> Unit
    ): PdfResult {
        return withContext(Dispatchers.IO) {
            try {
                PdfResult.Error("Composable-to-PDF rendering not yet implemented for JVM/Desktop. Coming soon!")
            } catch (e: Exception) {
                PdfResult.Error("Failed to generate PDF", e)
            }
        }
    }
}
