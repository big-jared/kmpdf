package io.github.bigboyapps.kmpdf

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsOwner
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import java.util.zip.Deflater
import kotlin.time.Duration
import android.graphics.Canvas as AndroidCanvas

private val logger = Logger.withTag("KmPdfGenerator")

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

actual suspend fun readPdfBytes(uri: String): ByteArray = withContext(Dispatchers.IO) {
    // FileProvider gives content:// URIs; without one, generated PDFs have file: URIs like file:/data/...
    if (uri.startsWith("content:") || uri.startsWith("file:")) {
        val context = applicationContext
            ?: throw IllegalStateException("KmPdfGenerator not initialized. Call initKmPdfGenerator(context) first.")
        context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
            ?: throw FileNotFoundException("Couldn't open $uri")
    } else {
        File(uri).readBytes()
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
        val context = applicationContext ?: return PdfResult.Error.NotInitialized().also {
            logger.e { "PDF generation failed: KmPdfGenerator not initialized" }
        }
        return generatePdfWith(AndroidPdfPlatform(context), config, pages, logger)
    }
}

/** Renders pages in an offscreen view on the current Activity, and saves PDFs to the cache directory. */
private class AndroidPdfPlatform(private val context: Context) : PdfPlatform {
    override suspend fun measureContentHeightsPx(
        contents: List<@Composable () -> Unit>,
        widthPx: Int,
        contentTimeout: Duration
    ): List<Int> {
        var heightsPx = emptyList<Int>()
        render({ MeasureContentHeights(contents) { heightsPx = it } }, widthPx, 1, contentTimeout) { _, _ -> }
        return heightsPx
    }

    override suspend fun renderPage(
        content: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int,
        contentTimeout: Duration
    ): RenderedPage = render(content, widthPx, heightPx, contentTimeout) { view, semanticsOwner ->
        val bitmap = createBitmap(widthPx, heightPx)
        try {
            // Drawing onto white flattens transparent areas onto a white page
            bitmap.eraseColor(Color.WHITE)
            view.draw(AndroidCanvas(bitmap))
            RenderedPage(bitmap.toRgb(), widthPx, heightPx, semanticsOwner.pageTextLines(widthPx, heightPx))
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun compress(data: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 4)
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    override suspend fun save(pdf: ByteArray, config: PdfConfig, pageCount: Int): PdfResult.Success =
        withContext(Dispatchers.IO) {
            val outputDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
            val outputFile = File(outputDir, config.fileName)
            // Write to a temporary file first so an earlier PDF is only replaced by a complete one
            val tempFile = File(outputDir, "${config.fileName}.partial")
            try {
                tempFile.writeBytes(pdf)
                check(tempFile.renameTo(outputFile)) { "Failed to move the PDF into place at $outputFile" }
            } finally {
                tempFile.delete()
            }
            logger.logDebug { "PDF written to: ${outputFile.absolutePath}" }

            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outputFile
                ).toString()
            } catch (e: Exception) {
                outputFile.toURI().toString()
            }
            PdfResult.Success(
                uri = uri,
                filePath = outputFile.absolutePath,
                fileSize = outputFile.length(),
                pageCount = pageCount
            )
        }

    /**
     * Renders [content] until it's ready and hands the laid-out view to [capture], retrying with the
     * current foreground Activity if the one in use is torn down mid-render (e.g. the Activity is recreated
     * by a rotation or theme change). If no live Activity can be acquired across
     * [MAX_PAGE_RENDER_ATTEMPTS], a [PdfRenderingException] is thrown.
     */
    private suspend fun <T> render(
        content: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int,
        contentTimeout: Duration,
        capture: (View, SemanticsOwner) -> T
    ): T = withContext(Dispatchers.Main) {
        var lastError: Throwable? = null
        repeat(MAX_PAGE_RENDER_ATTEMPTS) {
            val activity = liveActivity()
            if (activity == null) {
                lastError = IllegalStateException("No live Activity available for rendering")
                delay(ACTIVITY_REACQUIRE_DELAY_MS)
                return@repeat
            }
            try {
                return@withContext renderOnActivity(activity, content, widthPx, heightPx, contentTimeout, capture)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfContentTimeoutException) {
                throw e
            } catch (e: Exception) {
                if (!activity.wasTornDown()) {
                    // Failures that aren't caused by Activity recreation, like page content throwing,
                    // won't succeed on a retry
                    throw e
                }
                // The host window was torn down mid-render (Activity recreated). Re-acquire the
                // now-current Activity and try again instead of crashing.
                logger.logDebug { "Page render attempt failed (${e.message}); retrying" }
                lastError = e
                delay(ACTIVITY_REACQUIRE_DELAY_MS)
            }
        }
        throw PdfRenderingException(
            "No live Activity after $MAX_PAGE_RENDER_ATTEMPTS attempts: ${lastError?.message}",
            lastError
        )
    }

    private suspend fun <T> renderOnActivity(
        activity: Activity,
        content: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int,
        contentTimeout: Duration,
        capture: (View, SemanticsOwner) -> T
    ): T {
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
                        LocalDensity provides Density(PAGE_RENDER_SCALE),
                        LocalPdfContentTracker provides tracker
                    ) {
                        content()
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

            // The ComposeView hosts the composition in its only child, which also owns the semantics tree
            val root = composeView.getChildAt(0) as? ViewRootForTest
                ?: throw IllegalStateException("Compose content view is missing")
            return capture(composeView, root.semanticsOwner)
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
}

/** Reads an opaque bitmap as RGB bytes. */
private fun Bitmap.toRgb(): ByteArray {
    val rgb = ByteArray(width * height * 3)
    val row = IntArray(width)
    var dst = 0
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) {
            rgb[dst] = (pixel shr 16).toByte()
            rgb[dst + 1] = (pixel shr 8).toByte()
            rgb[dst + 2] = pixel.toByte()
            dst += 3
        }
    }
    return rgb
}
