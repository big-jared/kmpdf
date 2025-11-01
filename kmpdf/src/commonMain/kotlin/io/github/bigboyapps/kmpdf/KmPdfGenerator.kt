package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class PageSize(
    val width: Dp,
    val height: Dp
) {
    companion object {
        val A4 = PageSize(width = 595.dp, height = 842.dp)
        val Letter = PageSize(width = 612.dp, height = 792.dp)
        val Legal = PageSize(width = 612.dp, height = 1008.dp)
        val A3 = PageSize(width = 842.dp, height = 1191.dp)
        val A5 = PageSize(width = 420.dp, height = 595.dp)
        val Tabloid = PageSize(width = 792.dp, height = 1224.dp)
    }
}

data class PageMargins(
    val top: Dp = 72.dp,
    val bottom: Dp = 72.dp,
    val left: Dp = 72.dp,
    val right: Dp = 72.dp
) {
    companion object {
        val None = PageMargins(0.dp, 0.dp, 0.dp, 0.dp)
        val Normal = PageMargins(72.dp, 72.dp, 72.dp, 72.dp)
        val Narrow = PageMargins(36.dp, 36.dp, 36.dp, 36.dp)
        val Wide = PageMargins(108.dp, 108.dp, 108.dp, 108.dp)
    }
}

data class PdfConfig(
    val pageSize: PageSize = PageSize.A4,
    val margins: PageMargins = PageMargins.Normal,
    val fileName: String = "document.pdf"
)

interface KmPdfGenerator {
    suspend fun generatePdf(
        config: PdfConfig = PdfConfig(),
        content: @Composable () -> Unit
    ): PdfResult
}

sealed class PdfResult {
    data class Success(val uri: String) : PdfResult()
    data class Error(val message: String, val exception: Throwable? = null) : PdfResult()
}

expect fun createKmPdfGenerator(): KmPdfGenerator

expect fun sharePdf(uri: String, title: String = "Share PDF")
