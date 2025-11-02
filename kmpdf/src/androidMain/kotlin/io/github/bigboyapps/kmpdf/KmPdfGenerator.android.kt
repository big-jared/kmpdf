package io.github.bigboyapps.kmpdf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
        logger.logDebug { "Starting PDF generation: ${config.fileName}" }
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

                val widthPt = config.pageSize.width.value.toInt()
                val heightPt = config.pageSize.height.value.toInt()

                val scale = 2f
                val widthPx = (widthPt * scale).toInt()
                val heightPx = (heightPt * scale).toInt()

                logger.logDebug { "Rendering ${pageContents.size} pages at ${widthPt}x${heightPt}pt" }

                val pageBitmaps = pageContents.mapIndexed { index, pageContent ->
                    logger.logDebug { "Rendering page ${index + 1} of ${pageContents.size}" }

                    var composeView: ComposeView? = null
                    var parentView: ViewGroup? = null

                    try {
                        composeView = ComposeView(activity).apply {
                            setContent {
                                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                                    pageContent()
                                }
                            }
                            alpha = 0f
                            translationX = -10000f
                            translationY = -10000f
                            clipToPadding = false
                            clipChildren = false
                        }

                        parentView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                        val layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
                        parentView?.addView(composeView, layoutParams)

                        delay(200)

                        composeView.measure(
                            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
                        )
                        composeView.layout(0, 0, widthPx, heightPx)

                        delay(100)

                        val bitmap = withContext(Dispatchers.IO) {
                            createBitmap(widthPx, heightPx)
                        }
                        val canvas = AndroidCanvas(bitmap)
                        composeView.draw(canvas)

                        bitmap
                    } finally {
                        composeView?.let { view ->
                            parentView?.removeView(view)
                        }
                    }
                }

                logger.logDebug { "All pages rendered, creating PDF" }

                val pdfResult = withContext(Dispatchers.IO) {
                    try {
                        val pdfDocument = PdfDocument()

                        pageBitmaps.forEachIndexed { index, bitmap ->
                            val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
                            val page = pdfDocument.startPage(pageInfo)

                            val scaleDown = 1f / scale

                            page.canvas.save()
                            page.canvas.scale(scaleDown, scaleDown)
                            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                            page.canvas.restore()

                            pdfDocument.finishPage(page)
                            bitmap.recycle()
                        }

                        val outputDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                        val outputFile = File(outputDir, config.fileName)

                        FileOutputStream(outputFile).use { outputStream ->
                            pdfDocument.writeTo(outputStream)
                        }
                        pdfDocument.close()
                        logger.logDebug { "PDF written to: ${outputFile.absolutePath}" }

                        val fileSize = outputFile.length()

                        val uri = try {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                outputFile
                            ).toString()
                        } catch (e: Exception) {
                            outputFile.toURI().toString()
                        }

                        logger.logInfo { "PDF generation successful: $uri (${pageBitmaps.size} pages, $fileSize bytes)" }
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
