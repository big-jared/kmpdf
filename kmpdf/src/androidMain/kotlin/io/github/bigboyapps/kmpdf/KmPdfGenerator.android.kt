package io.github.bigboyapps.kmpdf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

actual fun createKmPdfGenerator(): KmPdfGenerator = AndroidKmPdfGenerator()

private var applicationContext: Context? = null
private var activityRef: WeakReference<Activity>? = null

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
            Uri.parse(uri)
        } else {
            // Convert file:// to content:// if needed
            val file = File(Uri.parse(uri).path ?: return)
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
        content: @Composable () -> Unit
    ): PdfResult {
        return withContext(Dispatchers.Main) {
            var composeView: ComposeView? = null
            var parentView: ViewGroup? = null

            try {
                val context = applicationContext
                    ?: return@withContext PdfResult.Error("KmPdfGenerator not initialized. Call initKmPdfGenerator(context) first.")

                val activity = activityRef?.get()
                    ?: return@withContext PdfResult.Error("Activity reference lost. Please reinitialize KmPdfGenerator.")

                // Calculate page dimensions in pixels
                val density = context.resources.displayMetrics.density
                val widthPx = (config.pageSize.width.value * density).toInt()
                val heightPx = (config.pageSize.height.value * density).toInt()

                // Create a ComposeView and add it to the activity's content view
                composeView = ComposeView(activity).apply {
                    setContent {
                        content()
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

                // Wait for composition and layout
                delay(200) // Give time for composition to complete

                // Measure and layout
                composeView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
                )
                composeView.layout(0, 0, widthPx, heightPx)

                // Create bitmap and draw
                val bitmap = withContext(Dispatchers.IO) {
                    Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                }

                val canvas = AndroidCanvas(bitmap)
                composeView.draw(canvas)

                // Remove the view from parent
                parentView?.removeView(composeView)
                composeView = null
                parentView = null

                // Create PDF on IO thread
                val pdfResult = withContext(Dispatchers.IO) {
                    try {
                        val pdfDocument = PdfDocument()
                        val pageInfo = PdfDocument.PageInfo.Builder(widthPx, heightPx, 1).create()
                        val page = pdfDocument.startPage(pageInfo)

                        // Draw the bitmap onto the PDF page
                        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        pdfDocument.finishPage(page)

                        // Save PDF to file
                        val outputDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
                        val outputFile = File(outputDir, config.fileName)

                        FileOutputStream(outputFile).use { outputStream ->
                            pdfDocument.writeTo(outputStream)
                        }
                        pdfDocument.close()

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
                        PdfResult.Error("Failed to create PDF: ${e.message}", e)
                    }
                }

                pdfResult
            } catch (e: Exception) {
                // Clean up in case of error
                composeView?.let { view ->
                    parentView?.removeView(view)
                }
                PdfResult.Error("Failed to generate PDF: ${e.message}", e)
            }
        }
    }
}
