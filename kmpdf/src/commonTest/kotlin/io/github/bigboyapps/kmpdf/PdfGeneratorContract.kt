package io.github.bigboyapps.kmpdf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import io.github.bigboyapps.kmpdf.testing.DetailedContent
import io.github.bigboyapps.kmpdf.testing.MARKER_CENTER_DP
import io.github.bigboyapps.kmpdf.testing.MarkerPage
import io.github.bigboyapps.kmpdf.testing.OversizedContent
import io.github.bigboyapps.kmpdf.testing.PageMarkerColors
import io.github.bigboyapps.kmpdf.testing.Rgb
import io.github.bigboyapps.kmpdf.testing.RgbaImage
import io.github.bigboyapps.kmpdf.testing.TestPageException
import io.github.bigboyapps.kmpdf.testing.TransparentContent
import io.github.bigboyapps.kmpdf.testing.assertPixelsDiffer
import io.github.bigboyapps.kmpdf.testing.assertPixelsMatch
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A generated PDF read back with the platform's own PDF engine. */
class ReadBackDocument(
    /** Each page's MediaBox width and height, in points. */
    val pageSizesPt: List<Pair<Float, Float>>,
    /** Each page rendered at [PdfGeneratorContract.RENDER_SCALE]x, or empty when rendering wasn't requested. */
    val pages: List<RgbaImage>
)

/**
 * Behavior every platform's generator must have, validated by reading its PDFs back.
 *
 * Each platform subclasses this in its test source set and supplies how to run a coroutine test, read a
 * PDF back with its own PDF engine, and render a composable directly as a reference.
 */
abstract class PdfGeneratorContract {
    protected abstract fun runPdfTest(block: suspend CoroutineScope.() -> Unit): TestResult

    protected abstract fun createGenerator(): KmPdfGenerator

    protected open fun config(fileName: String, pageSize: PageSize = PageSize.A4): PdfConfig =
        PdfConfig(pageSize = pageSize, fileName = fileName)

    protected abstract suspend fun readBack(result: PdfResult.Success, renderPages: Boolean = true): ReadBackDocument

    /** Renders [content] directly, without a PDF, at the given pixel size and [RENDER_SCALE] density. */
    protected abstract suspend fun renderReference(content: @Composable () -> Unit, widthPx: Int, heightPx: Int): RgbaImage

    /** A reference render composited onto white, the way every PDF page shows it. */
    private suspend fun referenceOnWhite(content: @Composable () -> Unit, widthPx: Int, heightPx: Int): RgbaImage =
        renderReference(content, widthPx, heightPx).flattenedOnWhite()

    /** Whether a PDF with [fileName] exists where the generator writes its output. */
    protected abstract fun outputExists(fileName: String): Boolean

    /** The page size, in points, the platform is expected to produce for a requested size. */
    protected open fun expectedPageSizePt(width: Float, height: Float): Pair<Float, Float> = width to height

    private suspend fun generate(
        fileName: String,
        pageSize: PageSize = PageSize.A4,
        pages: PdfPageScope.() -> Unit
    ): PdfResult = createGenerator().generatePdf(config(fileName, pageSize), pages)

    private suspend fun generateSuccessfully(
        fileName: String,
        pageSize: PageSize = PageSize.A4,
        pages: PdfPageScope.() -> Unit
    ): PdfResult.Success {
        val result = generate(fileName, pageSize, pages)
        assertIs<PdfResult.Success>(result, "Expected $fileName to generate successfully, got $result")
        return result
    }

    @Test
    fun pageMatchesReferenceRender() = runPdfTest {
        val result = generateSuccessfully("contract-detailed.pdf") { page { DetailedContent() } }
        val page = readBack(result).pages.single()

        assertPixelsMatch(referenceOnWhite({ DetailedContent() }, page.width, page.height), page, "Detailed content")
    }

    @Test
    fun wrongReferencesDontMatch() = runPdfTest {
        val result = generateSuccessfully("contract-negative.pdf") { page { DetailedContent() } }
        val page = readBack(result).pages.single()

        assertPixelsDiffer(
            referenceOnWhite({ DetailedContent(shift = 4.dp) }, page.width, page.height),
            page,
            "Content shifted by 4 dp"
        )
        assertPixelsDiffer(
            referenceOnWhite({ DetailedContent(accent = Color(0xFFD81B60)) }, page.width, page.height),
            page,
            "Different accent color"
        )
        assertPixelsDiffer(
            referenceOnWhite({ DetailedContent(text = "KmPDF 0123456788") }, page.width, page.height),
            page,
            "One changed character"
        )
    }

    @Test
    fun pagesComeBackInOrder() = runPdfTest {
        val colors = PageMarkerColors.take(4)
        val result = generateSuccessfully("contract-order.pdf") {
            colors.forEach { color -> page { MarkerPage(color) } }
        }

        assertEquals(colors.size, result.pageCount)
        val document = readBack(result)
        assertEquals(colors.size, document.pages.size)
        document.pages.forEachIndexed { index, page ->
            val markerPx = (MARKER_CENTER_DP * RENDER_SCALE).toInt()
            val sampled = page.pixel(markerPx, markerPx)
            val expected = colors[index].toRgb()
            assertTrue(
                sampled.distanceTo(expected) <= COLOR_TOLERANCE,
                "Page ${index + 1} marker should be $expected but was $sampled"
            )
        }
    }

    @Test
    fun standardAndCustomPageSizes() = runPdfTest {
        val sizes = listOf(
            PageSize.A4,
            PageSize.Letter,
            PageSize(width = 842.dp, height = 595.dp),
            PageSize(width = 612.5.dp, height = 792.25.dp)
        )
        sizes.forEachIndexed { index, size ->
            val result = generateSuccessfully("contract-size-$index.pdf", size) { page { MarkerPage(PageMarkerColors[index]) } }
            val (width, height) = readBack(result, renderPages = false).pageSizesPt.single()
            val (expectedWidth, expectedHeight) = expectedPageSizePt(size.width.value, size.height.value)
            assertTrue(abs(width - expectedWidth) <= SIZE_TOLERANCE_PT, "Size $index width: expected $expectedWidth, got $width")
            assertTrue(abs(height - expectedHeight) <= SIZE_TOLERANCE_PT, "Size $index height: expected $expectedHeight, got $height")
        }
    }

    @Test
    fun transparentContentRendersOnWhite() = runPdfTest {
        val result = generateSuccessfully("contract-transparent.pdf") { page { TransparentContent() } }
        val page = readBack(result).pages.single()

        val corner = page.pixel(page.width - 10, page.height - 10)
        assertTrue(corner.distanceTo(Rgb(255, 255, 255)) <= COLOR_TOLERANCE, "Uncovered area should be white, was $corner")
        assertPixelsMatch(referenceOnWhite({ TransparentContent() }, page.width, page.height), page, "Transparent content")
    }

    @Test
    fun oversizedContentIsClippedToThePage() = runPdfTest {
        val result = generateSuccessfully("contract-oversized.pdf", PageSize.Letter) { page { OversizedContent() } }
        val document = readBack(result)

        val (width, height) = document.pageSizesPt.single()
        val (expectedWidth, expectedHeight) = expectedPageSizePt(PageSize.Letter.width.value, PageSize.Letter.height.value)
        assertTrue(abs(width - expectedWidth) <= SIZE_TOLERANCE_PT && abs(height - expectedHeight) <= SIZE_TOLERANCE_PT)
        val page = document.pages.single()
        assertPixelsMatch(referenceOnWhite({ OversizedContent() }, page.width, page.height), page, "Oversized content")
    }

    @Test
    fun emptyPageListFails() = runPdfTest {
        val result = generate("contract-empty.pdf") { }

        assertIs<PdfResult.Error>(result)
        assertFalse(outputExists("contract-empty.pdf"), "No file should be written for an empty document")
    }

    @Test
    fun throwingPageFailsWithoutLeavingAFile() = runPdfTest {
        val result = generate("contract-throwing.pdf") {
            page { MarkerPage(PageMarkerColors[0]) }
            page { throw TestPageException() }
        }

        assertIs<PdfResult.Error.RenderingFailed>(result, "Expected RenderingFailed, got $result")
        assertFalse(outputExists("contract-throwing.pdf"), "A failed render shouldn't leave a PDF behind")

        // The failure mustn't leave rendering broken for the next document
        val recovered = generateSuccessfully("contract-after-throwing.pdf") { page { MarkerPage(PageMarkerColors[1]) } }
        assertEquals(1, readBack(recovered, renderPages = false).pageSizesPt.size)
    }

    @Test
    fun cancellationPropagates() = runPdfTest {
        var result: PdfResult? = null
        lateinit var job: Job
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            result = generate("contract-cancelled.pdf") {
                page { MarkerPage(PageMarkerColors[0]) }
                page {
                    // Cancel while pages are being rendered
                    job.cancel()
                    MarkerPage(PageMarkerColors[1])
                }
                page { MarkerPage(PageMarkerColors[2]) }
            }
        }

        job.start()
        job.join()

        assertTrue(job.isCancelled, "Generation should have been cancelled")
        assertNull(result, "A cancelled generation shouldn't return a result")
        assertFalse(outputExists("contract-cancelled.pdf"), "A cancelled generation shouldn't leave a PDF behind")
    }

    @Test
    fun marginsInsetContentOnEverySide() = runPdfTest {
        val margins = PdfMargins(left = 36.dp, top = 72.dp, right = 54.dp, bottom = 90.dp)
        val result = createGenerator().generatePdf(config("contract-margins.pdf").copy(margins = margins)) {
            page { Box(Modifier.fillMaxSize().background(Color.Red)) }
        }
        assertIs<PdfResult.Success>(result, "Expected a PDF with margins, got $result")
        val page = readBack(result).pages.single()

        val left = 36 * RENDER_SCALE
        val top = 72 * RENDER_SCALE
        val right = page.width - 54 * RENDER_SCALE
        val bottom = page.height - 90 * RENDER_SCALE
        val midX = page.width / 2
        val midY = page.height / 2
        val white = Rgb(255, 255, 255)
        val red = Rgb(255, 0, 0)
        fun assertColor(x: Int, y: Int, expected: Rgb, where: String) {
            val actual = page.pixel(x, y)
            assertTrue(actual.distanceTo(expected) <= COLOR_TOLERANCE, "$where at ($x, $y) should be $expected but was $actual")
        }

        assertColor(left - EDGE_OFFSET_PX, midY, white, "Left margin")
        assertColor(left + EDGE_OFFSET_PX, midY, red, "Content at the left margin")
        assertColor(midX, top - EDGE_OFFSET_PX, white, "Top margin")
        assertColor(midX, top + EDGE_OFFSET_PX, red, "Content at the top margin")
        assertColor(right + EDGE_OFFSET_PX, midY, white, "Right margin")
        assertColor(right - EDGE_OFFSET_PX, midY, red, "Content at the right margin")
        assertColor(midX, bottom + EDGE_OFFSET_PX, white, "Bottom margin")
        assertColor(midX, bottom - EDGE_OFFSET_PX, red, "Content at the bottom margin")
        assertColor(5, 5, white, "Top-left corner")
        assertColor(page.width - 5, page.height - 5, white, "Bottom-right corner")
    }

    @Test
    fun marginsMatchReferenceAndClipContent() = runPdfTest {
        val margins = PdfMargins.Normal
        val result = createGenerator().generatePdf(config("contract-margins-detailed.pdf").copy(margins = margins)) {
            page { OversizedContent() }
        }
        assertIs<PdfResult.Success>(result, "Expected a PDF with margins, got $result")
        val page = readBack(result).pages.single()

        val reference = referenceOnWhite(
            {
                Box(
                    Modifier
                        .fillMaxSize()
                        .absolutePadding(margins.left, margins.top, margins.right, margins.bottom)
                        .clipToBounds()
                ) { OversizedContent() }
            },
            page.width,
            page.height
        )
        assertPixelsMatch(reference, page, "Oversized content inside Normal margins")
    }

    @Test
    fun marginsWithoutRoomForContentFail() = runPdfTest {
        val result = createGenerator().generatePdf(
            config("contract-no-room.pdf").copy(margins = PdfMargins.symmetric(horizontal = 300.dp))
        ) {
            page { MarkerPage(PageMarkerColors[0]) }
        }

        assertIs<PdfResult.Error>(result, "Expected an error for margins wider than the page, got $result")
        assertTrue(result.message.contains("Margins"), "Error should explain the margins problem: ${result.message}")
        assertFalse(outputExists("contract-no-room.pdf"))
    }

    @Test
    fun thirtyPageDocument() = runPdfTest {
        val result = generateSuccessfully("contract-thirty.pdf") {
            repeat(30) { i -> page { MarkerPage(PageMarkerColors[i % PageMarkerColors.size]) } }
        }

        assertEquals(30, result.pageCount)
        assertEquals(30, readBack(result, renderPages = false).pageSizesPt.size)
    }

    companion object {
        const val RENDER_SCALE = 2
        private const val COLOR_TOLERANCE = 12
        private const val SIZE_TOLERANCE_PT = 0.01f

        /** How far from a margin edge to sample, so resampling at the edge itself doesn't matter. */
        private const val EDGE_OFFSET_PX = 3

        private fun Color.toRgb() = Rgb((red * 255 + 0.5f).toInt(), (green * 255 + 0.5f).toInt(), (blue * 255 + 0.5f).toInt())
    }
}
