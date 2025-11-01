package io.github.bigboyapps.kmpdf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import android.graphics.Canvas as AndroidCanvas

private val logger = Logger.withTag("KmPdfGenerator")

actual fun createKmPdfGenerator(): KmPdfGenerator = AndroidKmPdfGenerator()

private var applicationContext: Context? = null
private var activityRef: WeakReference<Activity>? = null

/**
 * Initializes the KmPdfGenerator with the given context.
 *
 * **Note**: As of version 1.1.0, this function is called automatically via a ContentProvider
 * and manual initialization is typically not required. You only need to call this manually if:
 * - You disabled auto-initialization in your AndroidManifest.xml
 * - You need to re-initialize after the Activity reference was lost
 *
 * @param context The Android context. If this is an Activity, it will be held as a weak reference
 *                for rendering Composables. The application context is also stored for file operations.
 */
fun initKmPdfGenerator(context: Context) {
    applicationContext = context.applicationContext
    if (context is Activity) {
        activityRef = WeakReference(context)
    }
}

actual fun sharePdf(uri: String, title: String) {
    val activity = activityRef?.get() ?: return
    val context = applicationContext ?: return

    try {
        val contentUri = if (uri.startsWith("content://")) {
            uri.toUri()
        } else {
            // Convert file:// to content:// if needed
            val file = File(uri.toUri().path ?: return)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        activity.startActivity(Intent.createChooser(shareIntent, title))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

class AndroidKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        pages: PdfPageScope.() -> Unit
    ): PdfResult {
        logger.d { "Starting PDF generation: ${config.fileName}" }
        return withContext(Dispatchers.Main) {
            try {
                val context = applicationContext
                    ?: return@withContext PdfResult.Error.NotInitialized().also {
                        logger.e { "PDF generation failed: KmPdfGenerator not initialized" }
                    }

                val activity = activityRef?.get()
                    ?: return@withContext PdfResult.Error.ActivityLost().also {
                        logger.e { "PDF generation failed: Activity reference lost" }
                    }

                // Build pages
                val pageScope = PdfPageScope()
                pageScope.pages()
                val pageContents = pageScope.pages

                if (pageContents.isEmpty()) {
                    return@withContext PdfResult.Error.Unknown("No pages defined")
                }

                // Page dimensions in points
                val widthPt = config.pageSize.width.value.toInt()
                val heightPt = config.pageSize.height.value.toInt()

                // Use 2x scale for rendering to get good quality
                val scale = 2f
                val widthPx = (widthPt * scale).toInt()
                val heightPx = (heightPt * scale).toInt()

                logger.d { "Rendering ${pageContents.size} pages at ${widthPt}x${heightPt}pt" }

                // Render each page to a bitmap
                val pageBitmaps = pageContents.mapIndexed { index, pageContent ->
                    logger.d { "Rendering page ${index + 1} of ${pageContents.size}" }

                    var composeView: ComposeView? = null
                    var parentView: ViewGroup? = null

                    try {
                        // Create a ComposeView for this page
                        composeView = ComposeView(activity).apply {
                            setContent {
                                pageContent()
                            }
                            // Make it invisible - render off-screen
                            alpha = 0f
                            translationX = -10000f
                            translationY = -10000f
                        }

                        // Add to the activity's root view (hidden off-screen)
                        parentView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                        val layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
                        parentView?.addView(composeView, layoutParams)

                        // Wait for composition
                        delay(100)

                        // Measure and layout - Use UNSPECIFIED for height to allow full content
                        composeView.measure(
                            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )

                        // Get the measured height
                        val measuredHeight = composeView.measuredHeight

                        // Layout with the measured dimensions
                        composeView.layout(0, 0, widthPx, measuredHeight)

                        // Wait a bit more for layout to settle
                        delay(100)

                        // Create bitmap with measured height to capture all content
                        val fullBitmap = withContext(Dispatchers.IO) {
                            createBitmap(widthPx, measuredHeight)
                        }
                        val canvas = AndroidCanvas(fullBitmap)
                        composeView.draw(canvas)

                        // If content is larger than one page, we need to crop it
                        // For now, just scale it down to fit the page
                        val bitmap = if (measuredHeight > heightPx) {
                            // Content is taller than page - scale down
                            Bitmap.createScaledBitmap(fullBitmap, widthPx, heightPx, true).also {
                                fullBitmap.recycle()
                            }
                        } else {
                            // Content fits - create a page-sized bitmap and center the content
                            val pageBitmap = createBitmap(widthPx, heightPx)
                            val pageCanvas = AndroidCanvas(pageBitmap)
                            pageCanvas.drawColor(android.graphics.Color.WHITE)
                            pageCanvas.drawBitmap(fullBitmap, 0f, 0f, null)
                            fullBitmap.recycle()
                            pageBitmap
                        }

                        bitmap
                    } finally {
                        // Clean up
                        composeView?.let { view ->
                            parentView?.removeView(view)
                        }
                    }
                }

                logger.d { "All pages rendered, creating PDF" }

                // Create PDF on IO thread
                val pdfResult = withContext(Dispatchers.IO) {
                    try {
                        val pdfDocument = PdfDocument()

                        // Create PDF pages from bitmaps
                        pageBitmaps.forEachIndexed { index, bitmap ->
                            val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
                            val page = pdfDocument.startPage(pageInfo)

                            // Scale bitmap to fit page
                            val scaleX = widthPt.toFloat() / widthPx
                            val scaleY = heightPt.toFloat() / heightPx

                            page.canvas.save()
                            page.canvas.scale(scaleX, scaleY)
                            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                            page.canvas.restore()

                            pdfDocument.finishPage(page)
                            bitmap.recycle()
                        }

                        // Save PDF to file
                        val outputDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                        val outputFile = File(outputDir, config.fileName)

                        FileOutputStream(outputFile).use { outputStream ->
                            pdfDocument.writeTo(outputStream)
                        }
                        pdfDocument.close()
                        logger.d { "PDF written to: ${outputFile.absolutePath}" }

                        // Get file size
                        val fileSize = outputFile.length()

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

                        logger.i { "PDF generation successful: $uri (${pageBitmaps.size} pages, $fileSize bytes)" }
                        PdfResult.Success(
                            uri = uri,
                            filePath = outputFile.absolutePath,
                            fileSize = fileSize,
                            pageCount = pageBitmaps.size
                        )
                    } catch (e: Exception) {
                        logger.e(e) { "Failed to create PDF: ${e.message}" }
                        PdfResult.Error.IOError("Failed to create PDF: ${e.message}", e)
                    }
                }

                pdfResult
            } catch (e: Exception) {
                logger.e(e) { "Failed to generate PDF: ${e.message}" }
                PdfResult.Error.Unknown("Failed to generate PDF: ${e.message}", e)
            }
        }
    }
}
