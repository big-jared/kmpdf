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
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.time.Duration
import android.graphics.Canvas as AndroidCanvas

private val logger = Logger.withTag("KmPdfGenerator")

/** Supersampling factor applied while rasterizing each page for sharper output. */
private const val RENDER_SCALE = 2f

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

/**
 * Compose needs a lifecycle owner and a saved state registry owner from the view tree. Activities that
 * never called setContentView (or plain Activities) don't provide them, so fall back to the Activity's
 * own owners, or to an always-resumed owner for the lifetime of the offscreen render.
 */
private fun ComposeView.installViewTreeOwnersIfMissing(parent: View, activity: Activity) {
    val fallback by lazy { OffscreenViewTreeOwner() }
    if (parent.findViewTreeLifecycleOwner() == null) {
        setViewTreeLifecycleOwner(activity as? LifecycleOwner ?: fallback)
    }
    if (parent.findViewTreeSavedStateRegistryOwner() == null) {
        setViewTreeSavedStateRegistryOwner(activity as? SavedStateRegistryOwner ?: fallback)
    }
}

private class OffscreenViewTreeOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
}

/** Whether this Activity is going away or has been replaced, e.g. by recreation during a render. */
private fun Activity.wasTornDown(): Boolean = isFinishing || isDestroyed || liveActivity() !== this

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

            if (pageScope.pages.isEmpty()) {
                return@withContext PdfResult.Error.Unknown("No pages defined")
            }

            val plannedPages = try {
                planPages(config, pageScope.pages) { content, widthPx ->
                    var heightPx = 0
                    renderPage({ MeasureContentHeight(content) { heightPx = it } }, widthPx, 1, config.contentTimeout)
                        .recycle()
                    heightPx
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfConfigException) {
                return@withContext PdfResult.Error.Unknown(e.message ?: "Invalid page configuration")
            } catch (e: Exception) {
                logger.e(e) { "Failed to measure pages: ${e.message}" }
                return@withContext PdfResult.Error.RenderingFailed("Failed to measure pages: ${e.message}", e)
            }

            logger.logDebug { "Rendering ${plannedPages.size} pages" }

            val pdfDocument = PdfDocument()
            try {
                // Render and add one page at a time so only one page bitmap is in memory
                plannedPages.forEachIndexed { index, plannedPage ->
                    logger.logDebug { "Rendering page ${index + 1} of ${plannedPages.size}" }

                    // PdfDocument only supports whole-point page sizes; round up so content is never cut off
                    val widthPt = ceil(plannedPage.widthPt).toInt()
                    val heightPt = ceil(plannedPage.heightPt).toInt()
                    val widthPx = (widthPt * RENDER_SCALE).toInt()
                    val heightPx = (heightPt * RENDER_SCALE).toInt()

                    val bitmap = try {
                        renderPage(plannedPage.content, widthPx, heightPx, config.contentTimeout)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PdfRenderingException) {
                        logger.e(e) { "PDF rendering failed: ${e.message}" }
                        return@withContext PdfResult.Error.RenderingFailed(
                            e.message ?: "Failed to render PDF pages",
                            e.cause
                        )
                    }

                    try {
                        addPage(pdfDocument, bitmap, index, widthPt, heightPt)
                    } catch (e: Exception) {
                        logger.e(e) { "Failed to add page ${index + 1}: ${e.message}" }
                        return@withContext PdfResult.Error.IOError("Failed to create PDF: ${e.message}", e)
                    } finally {
                        bitmap.recycle()
                    }
                }

                logger.logDebug { "All pages rendered, writing PDF" }
                writePdf(context, config, pdfDocument, plannedPages.size)
            } finally {
                pdfDocument.close()
            }
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
        heightPx: Int,
        contentTimeout: Duration
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
                return renderPageOnActivity(activity, pageContent, widthPx, heightPx, contentTimeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfContentTimeoutException) {
                throw PdfRenderingException(e.message ?: "Page content didn't finish loading", e)
            } catch (e: Exception) {
                if (!activity.wasTornDown()) {
                    // Failures that aren't caused by Activity recreation, like page content throwing,
                    // won't succeed on a retry
                    throw PdfRenderingException("Failed to render page: ${e.message}", e)
                }
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
        heightPx: Int,
        contentTimeout: Duration
    ): Bitmap {
        val parentView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            ?: throw IllegalStateException("Host Activity has no content view")

        // A recomposer just for this page, so pending work in the app's own UI (like an animation)
        // doesn't keep the page from being ready
        val recomposer = Recomposer(AndroidUiDispatcher.Main)
        val recomposerJob = CoroutineScope(AndroidUiDispatcher.Main).launch {
            recomposer.runRecomposeAndApplyChanges()
        }
        val tracker = PdfContentTracker()

        var composeView: ComposeView? = null
        try {
            composeView = ComposeView(activity).apply {
                setParentCompositionContext(recomposer)
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(RENDER_SCALE),
                        LocalPdfContentTracker provides tracker
                    ) {
                        pageContent()
                    }
                }
                alpha = 0f
                translationX = OFFSCREEN_TRANSLATION
                translationY = OFFSCREEN_TRANSLATION
                clipToPadding = false
                clipChildren = false
                installViewTreeOwnersIfMissing(parentView, activity)
            }

            parentView.addView(composeView, FrameLayout.LayoutParams(widthPx, heightPx))

            val readiness = PageReadiness(contentTimeout)
            do {
                // Let effects, animations, and asynchronous loading make progress, then lay out again
                delay(FRAME_DELAY_MS)
                composeView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
                )
                composeView.layout(0, 0, widthPx, heightPx)
            } while (readiness.needsAnotherFrame(tracker.isLoading, recomposer.hasPendingWork))

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
            recomposer.cancel()
            recomposerJob.cancel()
        }
    }

    private fun addPage(
        pdfDocument: PdfDocument,
        bitmap: Bitmap,
        index: Int,
        widthPt: Int,
        heightPt: Int
    ) {
        val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
        val page = pdfDocument.startPage(pageInfo)

        val scaleDown = 1f / RENDER_SCALE

        page.canvas.save()
        page.canvas.scale(scaleDown, scaleDown)
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        page.canvas.restore()

        pdfDocument.finishPage(page)
    }

    private suspend fun writePdf(
        context: Context,
        config: PdfConfig,
        pdfDocument: PdfDocument,
        pageCount: Int
    ): PdfResult = withContext(Dispatchers.IO) {
        try {
            val outputDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
            val outputFile = File(outputDir, config.fileName)

            FileOutputStream(outputFile).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
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

            logger.logInfo { "PDF generation successful: $uri ($pageCount pages, $fileSize bytes)" }
            PdfResult.Success(
                uri = uri,
                filePath = outputFile.absolutePath,
                fileSize = fileSize,
                pageCount = pageCount
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to create PDF: ${e.message}" }
            PdfResult.Error.IOError("Failed to create PDF: ${e.message}", e)
        }
    }
}
