package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Holds capture of the current PDF page while its content is still loading.
 *
 * Pages are captured once their composition settles. Content that loads asynchronously, like images
 * fetched over the network or data read from a database, finishes after that, so report it here: the
 * page is captured once [isLoading] becomes false. Generation waits at most [PdfConfig.contentTimeout]
 * and then returns [PdfResult.Error.RenderingFailed].
 *
 * Outside PDF generation this does nothing, so the same composable can also be shown on screen.
 *
 * ```kotlin
 * page {
 *     val invoice by produceState<Invoice?>(null) { value = repository.loadInvoice() }
 *     PdfContentLoading(isLoading = invoice == null)
 *     invoice?.let { InvoiceContent(it) }
 * }
 * ```
 */
@Composable
fun PdfContentLoading(isLoading: Boolean) {
    val tracker = LocalPdfContentTracker.current ?: return
    DisposableEffect(tracker, isLoading) {
        if (isLoading) tracker.loadingStarted()
        onDispose {
            if (isLoading) tracker.loadingFinished()
        }
    }
}

/** Counts the [PdfContentLoading] calls that are currently loading on a page being rendered. */
internal class PdfContentTracker {
    private var loadingCount = 0

    val isLoading: Boolean get() = loadingCount > 0

    fun loadingStarted() {
        loadingCount++
    }

    fun loadingFinished() {
        loadingCount--
    }
}

internal val LocalPdfContentTracker = staticCompositionLocalOf<PdfContentTracker?> { null }

/** Raised when page content still reports [PdfContentLoading] after [PdfConfig.contentTimeout]. */
internal class PdfContentTimeoutException(timeout: Duration) :
    Exception("Page content was still loading (PdfContentLoading) after $timeout")

/** How long to wait between frames while page content settles. */
internal const val FRAME_DELAY_MS = 16L

/** Frame time advanced for each frame while page content settles. */
internal const val FRAME_NANOS = FRAME_DELAY_MS * 1_000_000L

/** Endless animations never settle, so a page is captured after this many settling frames instead of hanging. */
internal const val MAX_SETTLE_FRAMES = 30

/** Decides, after each rendered frame, whether a page needs another frame before it's captured. */
internal class PageReadiness(private val contentTimeout: Duration) {
    private val start = TimeSource.Monotonic.markNow()
    private var settleFrames = 0
    private var extraFrames = 0

    /**
     * @param isLoading Whether any [PdfContentLoading] on the page is still loading.
     * @param hasPendingWork Whether composition has pending recompositions or effects.
     * @return true when another frame is needed.
     * @throws PdfContentTimeoutException when content is still loading after the timeout.
     */
    fun needsAnotherFrame(isLoading: Boolean, hasPendingWork: Boolean): Boolean {
        val needsFrame = when {
            isLoading -> {
                if (start.elapsedNow() > contentTimeout) throw PdfContentTimeoutException(contentTimeout)
                true
            }
            hasPendingWork && settleFrames < MAX_SETTLE_FRAMES -> {
                settleFrames++
                true
            }
            else -> false
        }
        if (needsFrame) extraFrames++ else PdfRenderDiagnostics.lastPageExtraFrames = extraFrames
        return needsFrame
    }
}

/** Lets tests check how rendering behaved. */
internal object PdfRenderDiagnostics {
    /** How many frames after the first the most recently captured page needed. */
    var lastPageExtraFrames: Int = -1
}
