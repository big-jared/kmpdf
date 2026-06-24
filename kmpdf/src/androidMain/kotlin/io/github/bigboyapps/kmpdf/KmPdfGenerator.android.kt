package io.github.bigboyapps.kmpdf

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import android.graphics.Canvas as AndroidCanvas

private val logger = Logger.withTag("KmPdfGenerator")

/** Supersampling factor applied while rasterizing each page for sharper output. */
private const val RENDER_SCALE = 2f

/** Delay (ms) after attaching a page so its composition can settle before measuring. */
private const val COMPOSE_SETTLE_DELAY_MS = 200L

/** Delay (ms) after layout so any follow-up recomposition is applied before drawing. */
private const val DRAW_SETTLE_DELAY_MS = 100L

/** How many times a single page render is retried when the host Activity is torn down mid-render. */
private const val MAX_PAGE_RENDER_ATTEMPTS = 5

/** Delay (ms) between page-render attempts while waiting for a live Activity to become available. */
private const val ACTIVITY_REACQUIRE_DELAY_MS = 200L

/** Off-screen translation used to keep the transient render view out of view while it draws. */
private const val OFFSCREEN_TRANSLATION = -10_000f

actual fun createKmPdfGenerator(): KmPdfGenerator = AndroidKmPdfGenerator()

private var applicationContext: Context? = null

/** Activity supplied to [initKmPdfGenerator]; used as a fallback and by [sharePdf]. */
private var activityRef: WeakReference<Activity>? = null

/**
 * The current foreground Activity, tracked across recreation (rotation, theme/locale changes) by
 * [activityTracker]. Rendering always uses this rather than a single Activity captured at init
 * time, so a destroyed Activity is never reused — the root cause of the
 * "Cannot locate windowRecomposer" crash.
 */
private var currentActivityRef: WeakReference<Activity>? = null
private var activityTrackerRegistered = false

private fun liveActivity(): Activity? = currentActivityRef?.get() ?: activityRef?.get()

private val activityTracker = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() === activity) {
            currentActivityRef = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/**
 * Initializes the KmPdfGenerator with the given context.
 *
 * On Android this is normally invoked automatically at startup by [KmPdfInitializer] (a
 * ContentProvider), so manual initialization is usually unnecessary. When given an [Application]
 * (or any context whose application context is an [Application]) it registers a lifecycle listener
 * that keeps track of the current foreground Activity, so PDF generation always renders against a
 * live Activity and survives Activity recreation while generation is in progress.
 *
 * @param context The Android context. The application context is stored for file operations and
 *                Activity tracking. If [context] is an [Activity] it is also held as a
 *                [WeakReference] and used as a fallback and by [sharePdf].
 */
fun initKmPdfGenerator(context: Context) {
    applicationContext = context.applicationContext
    if (context is Activity) {
        activityRef = WeakReference(context)
        currentActivityRef = WeakReference(context)
    }
    (context.applicationContext as? Application)?.let { app ->
        if (!activityTrackerRegistered) {
            app.registerActivityLifecycleCallbacks(activityTracker)
            activityTrackerRegistered = true
        }
    }
}

actual fun sharePdf(uri: String, title: String) {
    val activity = liveActivity() ?: return
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

/** Raised when a page cannot be rendered (e.g. no live Activity, or repeated teardown mid-render). */
private class PdfRenderingException(message: String, cause: Throwable?) : Exception(message, cause)

class AndroidKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        pages: PdfPageScope.() -> Unit
    ): PdfResult {
        logger.logDebug { "Starting PDF generation: ${config.fileName}" }
        return withContext(Dispatchers.Main) {
            val context = applicationContext
                ?: return@withContext PdfResult.Error.NotInitialized().also {
                    logger.e { "PDF generation failed: KmPdfGenerator not initialized" }
                }

            val pageScope = PdfPageScope()
            pageScope.pages()
            val pageContents = pageScope.pages

            if (pageContents.isEmpty()) {
                return@withContext PdfResult.Error.Unknown("No pages defined")
            }

            val widthPt = config.pageSize.width.value.toInt()
            val heightPt = config.pageSize.height.value.toInt()
            val widthPx = (widthPt * RENDER_SCALE).toInt()
            val heightPx = (heightPt * RENDER_SCALE).toInt()

            logger.logDebug { "Rendering ${pageContents.size} pages at ${widthPt}x${heightPt}pt" }

            val pageBitmaps = try {
                pageContents.mapIndexed { index, pageContent ->
                    logger.logDebug { "Rendering page ${index + 1} of ${pageContents.size}" }
                    renderPage(pageContent, widthPx, heightPx)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfRenderingException) {
                logger.e(e) { "PDF rendering failed: ${e.message}" }
                return@withContext PdfResult.Error.RenderingFailed(
                    e.message ?: "Failed to render PDF pages",
                    e.cause
                )
            }

            logger.logDebug { "All pages rendered, creating PDF" }
            writePdf(context, config, pageBitmaps, widthPt, heightPt)
        }
    }

    /**
     * Renders a single page, retrying with the current foreground Activity if the one in use is
     * torn down mid-render (e.g. the Activity is recreated by a rotation or theme change). If no
     * live Activity can be acquired across [MAX_PAGE_RENDER_ATTEMPTS], a [PdfRenderingException] is
     * thrown so the caller can surface a recoverable [PdfResult.Error.RenderingFailed] rather than
     * letting the underlying crash propagate.
     */
    private suspend fun renderPage(
        pageContent: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        var lastError: Throwable? = null
        repeat(MAX_PAGE_RENDER_ATTEMPTS) {
            val activity = liveActivity()
            if (activity == null) {
                lastError = IllegalStateException("No live Activity available for rendering")
                delay(ACTIVITY_REACQUIRE_DELAY_MS)
                return@repeat
            }
            try {
                return renderPageOnActivity(activity, pageContent, widthPx, heightPx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // The host window was torn down mid-render (Activity recreated). Re-acquire the
                // now-current Activity and try again instead of crashing.
                logger.logDebug { "Page render attempt failed (${e.message}); retrying" }
                lastError = e
                delay(ACTIVITY_REACQUIRE_DELAY_MS)
            }
        }
        throw PdfRenderingException(
            "Failed to render page after $MAX_PAGE_RENDER_ATTEMPTS attempts: ${lastError?.message}",
            lastError
        )
    }

    private suspend fun renderPageOnActivity(
        activity: Activity,
        pageContent: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val parentView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            ?: throw IllegalStateException("Host Activity has no content view")

        var composeView: ComposeView? = null
        try {
            composeView = ComposeView(activity).apply {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(RENDER_SCALE)) {
                        pageContent()
                    }
                }
                alpha = 0f
                translationX = OFFSCREEN_TRANSLATION
                translationY = OFFSCREEN_TRANSLATION
                clipToPadding = false
                clipChildren = false
            }

            parentView.addView(composeView, FrameLayout.LayoutParams(widthPx, heightPx))

            delay(COMPOSE_SETTLE_DELAY_MS)

            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            composeView.layout(0, 0, widthPx, heightPx)

            delay(DRAW_SETTLE_DELAY_MS)

            val bitmap = createBitmap(widthPx, heightPx)
            composeView.draw(AndroidCanvas(bitmap))
            return bitmap
        } finally {
            composeView?.let { view ->
                try {
                    parentView.removeView(view)
                } catch (e: Exception) {
                    logger.logDebug { "Failed to detach render view: ${e.message}" }
                }
            }
        }
    }

    private suspend fun writePdf(
        context: Context,
        config: PdfConfig,
        pageBitmaps: List<Bitmap>,
        widthPt: Int,
        heightPt: Int
    ): PdfResult = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()

            pageBitmaps.forEachIndexed { index, bitmap ->
                val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val scaleDown = 1f / RENDER_SCALE

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to create PDF: ${e.message}" }
            PdfResult.Error.IOError("Failed to create PDF: ${e.message}", e)
        }
    }
}
