package io.github.bigboiapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

interface KmPdfGenerator {
    suspend fun generatePdf(
        content: PdfContent,
        fileName: String = "document.pdf"
    ): PdfResult
}

data class PdfContent(
    val title: String,
    val subtitle: String? = null,
    val qrCodeData: String,
    val additionalInfo: List<String> = emptyList(),
    val footer: String? = null
)

sealed class PdfResult {
    data class Success(val uri: String) : PdfResult()
    data class Error(val message: String, val exception: Throwable? = null) : PdfResult()
}

expect fun createKmPdfGenerator(): KmPdfGenerator

@Composable
expect fun rememberQrCodeBitmap(
    data: String,
    size: Int = 512
): ImageBitmap?
