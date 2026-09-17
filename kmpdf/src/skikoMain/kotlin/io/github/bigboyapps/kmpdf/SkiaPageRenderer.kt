package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import kotlin.time.Duration

/**
 * Renders a page offscreen with Skia, producing frames until its content is ready.
 *
 * The caller owns the returned image and must close it.
 *
 * @throws PdfContentTimeoutException when [PdfContentLoading] is still true after [contentTimeout].
 */
internal suspend fun renderPageImage(
    content: @Composable () -> Unit,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    contentTimeout: Duration
): Image {
    val tracker = PdfContentTracker()
    val scene = ImageComposeScene(
        width = widthPx,
        height = heightPx,
        density = density,
        content = {
            CompositionLocalProvider(LocalPdfContentTracker provides tracker) {
                content()
            }
        }
    )
    try {
        val readiness = PageReadiness(contentTimeout)
        var frameTimeNanos = 0L
        var image = scene.render(frameTimeNanos)
        try {
            while (readiness.needsAnotherFrame(tracker.isLoading, scene.hasInvalidations())) {
                // Let effects, animations, and asynchronous loading make progress before the next frame
                delay(FRAME_DELAY_MS)
                frameTimeNanos += FRAME_NANOS
                val next = scene.render(frameTimeNanos)
                image.close()
                image = next
            }
        } catch (e: Throwable) {
            image.close()
            throw e
        }
        return image
    } finally {
        scene.close()
    }
}

/** Measures how tall each of [contents] is at [widthPx] with no height limit, once they're ready. */
internal suspend fun measureContentHeightsPx(
    contents: List<@Composable () -> Unit>,
    widthPx: Int,
    density: Density,
    contentTimeout: Duration
): List<Int> {
    var heightsPx = emptyList<Int>()
    renderPageImage({ MeasureContentHeights(contents) { heightsPx = it } }, widthPx, 1, density, contentTimeout).close()
    return heightsPx
}
