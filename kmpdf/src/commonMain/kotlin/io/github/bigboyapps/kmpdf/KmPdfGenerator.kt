package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

interface KmPdfGenerator {
    suspend fun generatePdf(
        width: Dp = 595.dp,
        height: Dp = 842.dp,
        fileName: String = "document.pdf",
        content: @Composable () -> Unit
    ): PdfResult
}

sealed class PdfResult {
    data class Success(val uri: String) : PdfResult()
    data class Error(val message: String, val exception: Throwable? = null) : PdfResult()
}

expect fun createKmPdfGenerator(): KmPdfGenerator
