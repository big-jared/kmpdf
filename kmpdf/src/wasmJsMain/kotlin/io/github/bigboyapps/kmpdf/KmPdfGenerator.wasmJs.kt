package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createKmPdfGenerator(): KmPdfGenerator = WasmKmPdfGenerator()

class WasmKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        width: Dp,
        height: Dp,
        fileName: String,
        content: @Composable () -> Unit
    ): PdfResult {
        return withContext(Dispatchers.Default) {
            try {
                PdfResult.Error("Composable-to-PDF rendering not yet implemented for WASM. Coming soon!")
            } catch (e: Exception) {
                PdfResult.Error("Failed to generate PDF", e)
            }
        }
    }
}
