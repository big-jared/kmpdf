package io.github.bigboiapps.kmpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import io.github.alexzhirkevich.qrose.options.*
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

actual fun createKmPdfGenerator(): KmPdfGenerator = AndroidKmPdfGenerator()

private var applicationContext: Context? = null

fun initKmPdfGenerator(context: Context) {
    applicationContext = context.applicationContext
}

class AndroidKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(content: PdfContent, fileName: String): PdfResult {
        return withContext(Dispatchers.IO) {
            try {
                val context = applicationContext
                    ?: return@withContext PdfResult.Error("KmPdfGenerator not initialized. Call initKmPdfGenerator(context) first.")

                val pdfDocument = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                val paint = Paint()

                var yPosition = 80f

                paint.textSize = 24f
                paint.isFakeBoldText = true
                paint.color = android.graphics.Color.BLACK
                canvas.drawText(content.title, 50f, yPosition, paint)
                yPosition += 40f

                content.subtitle?.let {
                    paint.textSize = 16f
                    paint.isFakeBoldText = false
                    canvas.drawText(it, 50f, yPosition, paint)
                    yPosition += 40f
                }

                yPosition += 20f

                val qrBitmap = generateSimpleQrBitmap(content.qrCodeData, 400)
                val qrX = (pageInfo.pageWidth - 400f) / 2f
                canvas.drawBitmap(qrBitmap, qrX, yPosition, paint)
                yPosition += 420f

                if (content.additionalInfo.isNotEmpty()) {
                    yPosition += 20f
                    paint.textSize = 14f
                    content.additionalInfo.forEach { info ->
                        canvas.drawText(info, 50f, yPosition, paint)
                        yPosition += 25f
                    }
                }

                content.footer?.let {
                    paint.textSize = 12f
                    paint.color = android.graphics.Color.GRAY
                    canvas.drawText(it, 50f, pageInfo.pageHeight - 50f, paint)
                }

                pdfDocument.finishPage(page)

                val directory = File(context.cacheDir, "pdfs")
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                val file = File(directory, fileName)
                FileOutputStream(file).use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                }
                pdfDocument.close()

                PdfResult.Success(file.absolutePath)
            } catch (e: Exception) {
                PdfResult.Error("Failed to generate PDF", e)
            }
        }
    }

    private fun generateSimpleQrBitmap(data: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(android.graphics.Color.WHITE)

        val paint = Paint().apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
        }

        val moduleSize = size / 29f
        val hash = data.hashCode()

        for (row in 0 until 29) {
            for (col in 0 until 29) {
                val index = row * 29 + col
                if ((hash + index) % 2 == 0) {
                    canvas.drawRect(
                        col * moduleSize,
                        row * moduleSize,
                        (col + 1) * moduleSize,
                        (row + 1) * moduleSize,
                        paint
                    )
                }
            }
        }

        return bitmap
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
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
