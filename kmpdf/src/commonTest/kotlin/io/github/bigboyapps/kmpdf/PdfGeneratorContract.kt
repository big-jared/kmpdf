package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
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

        assertPixelsMatch(renderReference({ DetailedContent() }, page.width, page.height), page, "Detailed content")
    }

    @Test
    fun wrongReferencesDontMatch() = runPdfTest {
        val result = generateSuccessfully("contract-negative.pdf") { page { DetailedContent() } }
        val page = readBack(result).pages.single()

        assertPixelsDiffer(
            renderReference({ DetailedContent(shift = 4.dp) }, page.width, page.height),
            page,
            "Content shifted by 4 dp"
        )
        assertPixelsDiffer(
            renderReference({ DetailedContent(accent = Color(0xFFD81B60)) }, page.width, page.height),
            page,
            "Different accent color"
        )
        assertPixelsDiffer(
            renderReference({ DetailedContent(text = "KmPDF 0123456788") }, page.width, page.height),
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
        assertPixelsMatch(renderReference({ TransparentContent() }, page.width, page.height).flattenedOnWhite(), page, "Transparent content")
    }

    @Test
    fun oversizedContentIsClippedToThePage() = runPdfTest {
        val result = generateSuccessfully("contract-oversized.pdf", PageSize.Letter) { page { OversizedContent() } }
        val document = readBack(result)

        val (width, height) = document.pageSizesPt.single()
        val (expectedWidth, expectedHeight) = expectedPageSizePt(PageSize.Letter.width.value, PageSize.Letter.height.value)
        assertTrue(abs(width - expectedWidth) <= SIZE_TOLERANCE_PT && abs(height - expectedHeight) <= SIZE_TOLERANCE_PT)
        val page = document.pages.single()
        assertPixelsMatch(renderReference({ OversizedContent() }, page.width, page.height), page, "Oversized content")
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

        private fun Color.toRgb() = Rgb((red * 255 + 0.5f).toInt(), (green * 255 + 0.5f).toInt(), (blue * 255 + 0.5f).toInt())
    }
}
