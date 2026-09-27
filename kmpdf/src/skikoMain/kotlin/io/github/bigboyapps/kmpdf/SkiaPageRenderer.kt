@file:OptIn(InternalComposeUiApi::class)

package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import kotlin.time.Duration

/** A page rendered with Skia. The caller owns [image] and must close it. */
internal class SkiaRenderedPage(val image: Image, val textLines: List<PageTextLine>)

/**
 * Renders a page offscreen with Skia, producing frames until its content is ready, and collects its text.
 *
 * This builds the scene the same way Compose's ImageComposeScene does, but with a platform context that
 * reports the scene's semantics tree, which is where each Text's layout comes from. That hook is an
 * internal Compose API, so the text layer is tied to the Compose Multiplatform version KmPDF is built with.
 *
 * @throws PdfContentTimeoutException when [PdfContentLoading] is still true after [contentTimeout].
 */
internal suspend fun renderPageImage(
    content: @Composable () -> Unit,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    contentTimeout: Duration,
    collectText: Boolean = true
): SkiaRenderedPage {
    val tracker = PdfContentTracker()
    val semanticsOwners = mutableListOf<SemanticsOwner>()
    val platformContext = object : PlatformContext by PlatformContext.Empty {
        override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
            override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                semanticsOwners += semanticsOwner
            }

            override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                semanticsOwners -= semanticsOwner
            }

            override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit

            override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
        }
    }
    val scene = CanvasLayersComposeScene(
        density = density,
        size = IntSize(widthPx, heightPx),
        coroutineContext = Dispatchers.Unconfined,
        platformContext = platformContext,
        invalidate = {}
    )
    val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
    try {
        scene.setContent {
            CompositionLocalProvider(LocalPdfContentTracker provides tracker) {
                content()
            }
        }

        fun renderFrame(frameTimeNanos: Long): Image {
            surface.canvas.clear(org.jetbrains.skia.Color.TRANSPARENT)
            scene.render(surface.canvas.asComposeCanvas(), frameTimeNanos)
            return surface.makeImageSnapshot()
        }

        val readiness = PageReadiness(contentTimeout)
        var frameTimeNanos = 0L
        var image = renderFrame(frameTimeNanos)
        try {
            while (readiness.needsAnotherFrame(tracker.isLoading, scene.hasInvalidations())) {
                // Let effects, animations, and asynchronous loading make progress before the next frame
                delay(FRAME_DELAY_MS)
                frameTimeNanos += FRAME_NANOS
                val next = renderFrame(frameTimeNanos)
                image.close()
                image = next
            }
            val textLines = if (collectText) semanticsOwners.flatMap { it.pageTextLines(widthPx, heightPx) } else emptyList()
            return SkiaRenderedPage(image, textLines)
        } catch (e: Throwable) {
            image.close()
            throw e
        }
    } finally {
        scene.close()
        surface.close()
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
    renderPageImage(
        { MeasureContentHeights(contents) { heightsPx = it } },
        widthPx,
        1,
        density,
        contentTimeout,
        collectText = false
    ).image.close()
    return heightsPx
}

/** Renders pages with Skia; platforms add compression and saving. */
internal abstract class SkiaPdfPlatform : PdfPlatform {
    private val density = Density(PAGE_RENDER_SCALE)

    override suspend fun measureContentHeightsPx(
        contents: List<@Composable () -> Unit>,
        widthPx: Int,
        contentTimeout: Duration
    ): List<Int> = measureContentHeightsPx(contents, widthPx, density, contentTimeout)

    override suspend fun renderPage(
        content: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int,
        contentTimeout: Duration
    ): RenderedPage {
        val rendered = renderPageImage(content, widthPx, heightPx, density, contentTimeout)
        try {
            return RenderedPage(rendered.image.toRgbOnWhite(), widthPx, heightPx, rendered.textLines)
        } finally {
            rendered.image.close()
        }
    }
}

/** Reads the image as RGB bytes, flattening transparent areas onto a white page. */
internal fun Image.toRgbOnWhite(): ByteArray {
    val bitmap = Bitmap()
    try {
        bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        check(readPixels(bitmap, 0, 0)) { "Failed to read rendered page pixels" }
        val rgba = bitmap.readPixels() ?: error("Failed to read rendered page pixels")

        val rgb = ByteArray(width * height * 3)
        var src = 0
        var dst = 0
        while (dst < rgb.size) {
            // With premultiplied alpha, compositing over white is color + (255 - alpha)
            val background = 255 - (rgba[src + 3].toInt() and 0xFF)
            rgb[dst] = ((rgba[src].toInt() and 0xFF) + background).toByte()
            rgb[dst + 1] = ((rgba[src + 1].toInt() and 0xFF) + background).toByte()
            rgb[dst + 2] = ((rgba[src + 2].toInt() and 0xFF) + background).toByte()
            src += 4
            dst += 3
        }
        return rgb
    } finally {
        bitmap.close()
    }
}
