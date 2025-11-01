package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createKmPdfGenerator(): KmPdfGenerator = WasmKmPdfGenerator()

actual fun sharePdf(uri: String, title: String) {
    // WASM browser sharing - to be implemented
    println("Share PDF not yet implemented for WASM")
}

class WasmKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        pages: PdfPageScope.() -> Unit
    ): PdfResult {
        return withContext(Dispatchers.Default) {
            try {
                PdfResult.Error.Unknown("Composable-to-PDF rendering not yet implemented for WASM. Coming soon!")
            } catch (e: Exception) {
                PdfResult.Error.Unknown("Failed to generate PDF", e)
            }
        }
    }
}
