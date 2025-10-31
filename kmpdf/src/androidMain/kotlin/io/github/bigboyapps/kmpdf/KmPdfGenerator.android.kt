package io.github.bigboyapps.kmpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.pdf.PdfDocument
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
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
    override suspend fun generatePdf(
        config: PdfConfig,
        content: @Composable () -> Unit
    ): PdfResult {
        return withContext(Dispatchers.Main) {
            try {
                val context = applicationContext
                    ?: return@withContext PdfResult.Error("KmPdfGenerator not initialized. Call initKmPdfGenerator(context) first.")

                // Calculate page dimensions in pixels
                val density = context.resources.displayMetrics.density
                val widthPx = (config.pageSize.width.value * density).toInt()
                val heightPx = (config.pageSize.height.value * density).toInt()

                // Create a ComposeView to render the composable
                val composeView = ComposeView(context).apply {
                    setContent {
                        content()
                    }
                }

                // Measure and layout the view
                composeView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
                )
                composeView.layout(0, 0, widthPx, heightPx)

                // Wait for composition to complete
                withContext(Dispatchers.IO) {
                    Thread.sleep(100) // Give time for composition to settle
                }

                // Create bitmap and draw the view
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                val canvas = AndroidCanvas(bitmap)
                composeView.draw(canvas)

                // Create PDF document
                val pdfDocument = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(widthPx, heightPx, 1).create()
                val page = pdfDocument.startPage(pageInfo)

                // Draw the bitmap onto the PDF page
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)

                // Save PDF to file
                val outputDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                val outputFile = File(outputDir, config.fileName)

                withContext(Dispatchers.IO) {
                    FileOutputStream(outputFile).use { outputStream ->
                        pdfDocument.writeTo(outputStream)
                    }
                    pdfDocument.close()
                }

                // Clean up
                bitmap.recycle()

                // Return file URI
                val uri = try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        outputFile
                    ).toString()
                } catch (e: Exception) {
                    // Fallback to file:// URI if FileProvider is not configured
                    outputFile.toURI().toString()
                }

                PdfResult.Success(uri)
            } catch (e: Exception) {
                PdfResult.Error("Failed to generate PDF: ${e.message}", e)
            }
        }
    }
}
