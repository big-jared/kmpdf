package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.UIKit.*

actual fun createKmPdfGenerator(): KmPdfGenerator = IosKmPdfGenerator()

actual fun sharePdf(uri: String, title: String) {
    // iOS share sheet - to be implemented
    println("Share PDF not yet implemented for iOS")
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        content: @Composable () -> Unit
    ): PdfResult {
        return withContext(Dispatchers.IO) {
            try {
                PdfResult.Error("Composable-to-PDF rendering not yet implemented for iOS. Coming soon!")
            } catch (e: Exception) {
                PdfResult.Error("Failed to generate PDF", e)
            }
        }
    }
}
