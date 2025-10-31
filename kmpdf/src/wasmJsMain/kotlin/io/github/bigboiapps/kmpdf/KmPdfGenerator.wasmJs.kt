package io.github.bigboiapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.alexzhirkevich.qrose.options.*
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

actual fun createKmPdfGenerator(): KmPdfGenerator = WasmKmPdfGenerator()

class WasmKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(content: PdfContent, fileName: String): PdfResult {
        return withContext(Dispatchers.Default) {
            try {
                PdfResult.Error("PDF generation not yet implemented for WASM")
            } catch (e: Exception) {
                PdfResult.Error("Failed to generate PDF", e)
            }
        }
    }
}

@Composable
actual fun rememberQrCodeBitmap(data: String, size: Int): ImageBitmap? {
    val qrPainter = rememberQrCodePainter(data) {
        colors {
            dark = QrBrush.solid(Color.Black)
            light = QrBrush.solid(Color.White)
        }
        shapes {
            ball = QrBallShape.circle()
            darkPixel = QrPixelShape.roundCorners()
            frame = QrFrameShape.roundCorners(.25f)
        }
    }

    return remember(data, size) {
        try {
            val bitmap = Bitmap()
            val imageInfo = ImageInfo(
                width = size,
                height = size,
                colorType = ColorType.RGBA_8888,
                alphaType = ColorAlphaType.PREMUL
            )
            bitmap.allocPixels(imageInfo)
            bitmap.asComposeImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
