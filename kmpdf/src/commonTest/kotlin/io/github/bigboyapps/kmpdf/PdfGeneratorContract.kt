package io.github.bigboyapps.kmpdf

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.bigboyapps.kmpdf.testing.StackedBlocks
import io.github.bigboyapps.kmpdf.testing.decodeItemIndex
import io.github.bigboyapps.kmpdf.testing.footerColor
import io.github.bigboyapps.kmpdf.testing.itemColor
import io.github.bigboyapps.kmpdf.testing.TestPageException
import io.github.bigboyapps.kmpdf.testing.TransparentContent
import io.github.bigboyapps.kmpdf.testing.assertPixelsDiffer
import io.github.bigboyapps.kmpdf.testing.assertPixelsMatch
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

    /** Counts the pages in [bytes] with the platform's own PDF engine. */
    protected abstract suspend fun pageCountOf(bytes: ByteArray): Int

    /** Reads the document information back with the platform's own PDF engine (or the strict structure reader). */
    protected abstract suspend fun readMetadata(result: PdfResult.Success): Map<String, String>

    /** Whether KmPDF can set the producer. iOS's CoreGraphics always writes its own. */
    protected open val writesProducer: Boolean = true

    /** A URI that [readPdfBytes] can't read on this platform. */
    protected open val missingPdfUri: String = "/kmpdf-missing/does-not-exist.pdf"

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

    private fun assertColorAt(page: RgbaImage, x: Int, y: Int, expected: Rgb, what: String) {
        val actual = page.pixel(x, y)
        assertTrue(actual.distanceTo(expected) <= COLOR_TOLERANCE, "$what at ($x, $y) should be $expected but was $actual")
    }

    @Test
    fun stateChangedRightAfterCompositionIsCaptured() = runPdfTest {
        val result = generateSuccessfully("contract-effect.pdf") {
            page {
                var ready by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { ready = true }
                Box(Modifier.fillMaxSize().background(if (ready) Color.Green else Color.Red))
            }
        }
        val page = readBack(result).pages.single()

        assertColorAt(page, page.width / 2, page.height / 2, Rgb(0, 255, 0), "Page updated in a LaunchedEffect")
    }

    @Test
    fun loadingContentIsWaitedFor() = runPdfTest {
        val result = generateSuccessfully("contract-loading.pdf") {
            page {
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(500.milliseconds)
                    loaded = true
                }
                PdfContentLoading(isLoading = !loaded)
                Box(Modifier.fillMaxSize().background(if (loaded) Color.Green else Color.Red))
            }
        }
        val page = readBack(result).pages.single()

        assertColorAt(page, page.width / 2, page.height / 2, Rgb(0, 255, 0), "Page that loads for 500 ms")
    }

    @Test
    fun loadingThatNeverFinishesTimesOut() = runPdfTest {
        val timeout = 1.seconds
        val start = TimeSource.Monotonic.markNow()
        val result = createGenerator().generatePdf(config("contract-timeout.pdf").copy(contentTimeout = timeout)) {
            page {
                PdfContentLoading(isLoading = true)
                MarkerPage(PageMarkerColors[0])
            }
        }
        val elapsed = start.elapsedNow()

        assertIs<PdfResult.Error.RenderingFailed>(result, "Expected a timeout, got $result")
        assertTrue(result.message.contains("PdfContentLoading"), "Error should mention the loading timeout: ${result.message}")
        assertTrue(elapsed < timeout + 2.seconds, "Should fail soon after the ${timeout} timeout, took $elapsed")
        assertFalse(outputExists("contract-timeout.pdf"))
    }

    @Test
    fun endlessAnimationStillGenerates() = runPdfTest {
        val result = generateSuccessfully("contract-animation.pdf") {
            page {
                val transition = rememberInfiniteTransition()
                val alpha by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000)))
                Box(Modifier.fillMaxSize().background(Color.Blue.copy(alpha = alpha)))
            }
        }

        assertEquals(1, result.pageCount)
    }

    @Test
    fun staticPageNeedsNoExtraFrames() = runPdfTest {
        generateSuccessfully("contract-static.pdf") { page { DetailedContent() } }

        assertEquals(0, PdfRenderDiagnostics.lastPageExtraFrames, "A static page shouldn't wait for extra frames")
    }

    private fun assertPageSize(actual: Pair<Float, Float>, width: Float, height: Float, what: String) {
        val (expectedWidth, expectedHeight) = expectedPageSizePt(width, height)
        assertTrue(
            abs(actual.first - expectedWidth) <= SIZE_TOLERANCE_PT && abs(actual.second - expectedHeight) <= SIZE_TOLERANCE_PT,
            "$what should be $expectedWidth x $expectedHeight pt but was ${actual.first} x ${actual.second} pt"
        )
    }

    @Test
    fun wrapHeightPageFitsItsContent() = runPdfTest {
        val result = generateSuccessfully("contract-wrap.pdf") {
            page(size = PageSize.wrapHeight(400.dp)) { StackedBlocks() }
        }
        val document = readBack(result)

        assertPageSize(document.pageSizesPt.single(), 400f, 200f, "Wrap-height page")
        val page = document.pages.single()
        assertPixelsMatch(referenceOnWhite({ StackedBlocks() }, page.width, page.height), page, "Wrap-height content")
    }

    @Test
    fun wrapHeightIncludesVerticalMargins() = runPdfTest {
        val margins = PdfMargins.symmetric(horizontal = 20.dp, vertical = 72.dp)
        val result = createGenerator().generatePdf(config("contract-wrap-margins.pdf").copy(margins = margins)) {
            page(size = PageSize.wrapHeight(400.dp)) { StackedBlocks() }
        }
        assertIs<PdfResult.Success>(result, "Expected a wrap-height page with margins, got $result")
        val document = readBack(result)

        assertPageSize(document.pageSizesPt.single(), 400f, 344f, "Wrap-height page with 72 pt vertical margins")
        val page = document.pages.single()
        val top = 72 * RENDER_SCALE
        val bottom = page.height - 72 * RENDER_SCALE
        assertColorAt(page, page.width / 2, top - EDGE_OFFSET_PX, Rgb(255, 255, 255), "Top margin")
        assertColorAt(page, page.width / 2, top + EDGE_OFFSET_PX, Rgb(255, 0, 0), "Top of the content")
        assertColorAt(page, page.width / 2, bottom - EDGE_OFFSET_PX, Rgb(0, 0, 255), "Bottom of the content")
        assertColorAt(page, page.width / 2, bottom + EDGE_OFFSET_PX, Rgb(255, 255, 255), "Bottom margin")
    }

    @Test
    fun wrapHeightRoundsUpToWholePoints() = runPdfTest {
        val result = generateSuccessfully("contract-wrap-round.pdf") {
            page(size = PageSize.wrapHeight(300.dp)) {
                Box(Modifier.fillMaxWidth().height(100.3.dp).background(Color.Red))
            }
        }

        assertPageSize(readBack(result, renderPages = false).pageSizesPt.single(), 300f, 101f, "Page with 100.3 pt of content")
    }

    @Test
    fun emptyWrapHeightPageIsOnePointTall() = runPdfTest {
        val result = generateSuccessfully("contract-wrap-empty.pdf") {
            page(size = PageSize.wrapHeight(300.dp)) { }
        }

        assertPageSize(readBack(result, renderPages = false).pageSizesPt.single(), 300f, 1f, "Empty wrap-height page")
    }

    @Test
    fun wrapHeightOverThePdfPageLimitFails() = runPdfTest {
        val result = generate("contract-wrap-too-tall.pdf") {
            page(size = PageSize.wrapHeight(300.dp)) {
                Box(Modifier.fillMaxWidth().height(15_000.dp))
            }
        }

        assertIs<PdfResult.Error.RenderingFailed>(result, "Expected content over 14,400 pt to fail, got $result")
        assertTrue(result.message.contains("14400"), "Error should mention the page limit: ${result.message}")
        assertFalse(outputExists("contract-wrap-too-tall.pdf"))
    }

    @Test
    fun wrapHeightMeasuresLoadedContent() = runPdfTest {
        val result = generateSuccessfully("contract-wrap-loading.pdf") {
            page(size = PageSize.wrapHeight(300.dp)) {
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(300.milliseconds)
                    loaded = true
                }
                PdfContentLoading(isLoading = !loaded)
                Box(Modifier.fillMaxWidth().height(if (loaded) 250.dp else 50.dp).background(Color.Green))
            }
        }

        assertPageSize(readBack(result, renderPages = false).pageSizesPt.single(), 300f, 250f, "Page sized after loading")
    }

    @Test
    fun documentCanMixPageSizes() = runPdfTest {
        val result = generateSuccessfully("contract-mixed-sizes.pdf") {
            page { MarkerPage(PageMarkerColors[0]) }
            page(size = PageSize.Letter) { MarkerPage(PageMarkerColors[1]) }
            page(size = PageSize.wrapHeight(300.dp)) {
                Box(Modifier.fillMaxWidth().height(150.dp).background(PageMarkerColors[2]))
            }
        }
        val document = readBack(result)

        assertEquals(3, document.pageSizesPt.size)
        assertPageSize(document.pageSizesPt[0], 595f, 842f, "Page 1 (A4 from the config)")
        assertPageSize(document.pageSizesPt[1], 612f, 792f, "Page 2 (Letter)")
        assertPageSize(document.pageSizesPt[2], 300f, 150f, "Page 3 (wrap height)")
        val markerPx = MARKER_CENTER_DP * RENDER_SCALE
        assertColorAt(document.pages[0], markerPx, markerPx, PageMarkerColors[0].toRgb(), "Page 1 marker")
        assertColorAt(document.pages[1], markerPx, markerPx, PageMarkerColors[1].toRgb(), "Page 2 marker")
        assertColorAt(document.pages[2], document.pages[2].width / 2, document.pages[2].height / 2, PageMarkerColors[2].toRgb(), "Page 3 fill")
    }

    @Test
    fun itemsFlowAcrossPagesWithoutSplitting() = runPdfTest {
        val itemHeights = List(100) { index -> 20 + index * 37 % 60 }
        val margins = PdfMargins.Narrow
        val headerHeight = 30
        val footerHeight = 20
        val spacing = 4
        val result = createGenerator().generatePdf(config("contract-pagination.pdf", PageSize.Letter).copy(margins = margins)) {
            pages(
                items = itemHeights.indices.toList(),
                itemSpacing = spacing.dp,
                header = { Box(Modifier.fillMaxWidth().height(headerHeight.dp).background(Color.Black)) },
                footer = { info ->
                    Box(Modifier.fillMaxWidth().height(footerHeight.dp).background(footerColor(info.pageNumber, info.pageCount)))
                }
            ) { index ->
                Box(Modifier.fillMaxWidth().height(itemHeights[index].dp).background(itemColor(index)))
            }
        }
        assertIs<PdfResult.Success>(result, "Expected paginated items to generate, got $result")

        // Letter (792 pt) minus 36 pt margins, the header, and the footer
        val available = 792f - 72f - headerHeight - footerHeight
        val expectedPages = (packIntoPages(itemHeights.map { it.toFloat() }, available, spacing.toFloat()) as PagePacking.Packed).pages
        val document = readBack(result)
        assertEquals(expectedPages.size, result.pageCount, "Page count should match the packing")
        assertEquals(expectedPages.size, document.pages.size)

        val itemsTop = (36 + headerHeight) * RENDER_SCALE
        document.pages.forEachIndexed { pageIndex, page ->
            val range = expectedPages[pageIndex]
            val midX = page.width / 2
            val first = decodeItemIndex(page.pixel(midX, itemsTop + EDGE_OFFSET_PX))
            val itemsHeight = range.sumOf { itemHeights[it] } + spacing * (range.count() - 1)
            val last = decodeItemIndex(page.pixel(midX, itemsTop + itemsHeight * RENDER_SCALE - EDGE_OFFSET_PX))
            assertEquals(range.first, first, "First item on page ${pageIndex + 1}")
            assertEquals(range.last, last, "Last item on page ${pageIndex + 1}")

            val footerY = page.height - (36 + footerHeight / 2) * RENDER_SCALE
            assertColorAt(page, midX, footerY, footerColor(pageIndex + 1, expectedPages.size).toRgb(), "Footer on page ${pageIndex + 1}")
        }
    }

    @Test
    fun pageInfoIsProvidedToEveryPage() = runPdfTest {
        val pageContent: @Composable () -> Unit = {
            val info = LocalPdfPageInfo.current
            Box(Modifier.fillMaxSize().background(footerColor(info.pageNumber, info.pageCount)))
        }
        val result = generateSuccessfully("contract-page-info.pdf") {
            page(pageContent)
            pages(items = listOf(1, 2)) { Box(Modifier.fillMaxWidth().height(500.dp)) { pageContent() } }
            page(size = PageSize.Letter, content = pageContent)
        }
        val document = readBack(result)

        assertEquals(4, document.pages.size, "1 page, 2 flowing pages of one 500 pt item each, then 1 page")
        document.pages.forEachIndexed { index, page ->
            assertColorAt(page, page.width / 2, 100, footerColor(index + 1, 4).toRgb(), "Page info on page ${index + 1}")
        }
    }

    @Test
    fun itemTallerThanAPageFails() = runPdfTest {
        val result = generate("contract-item-too-tall.pdf") {
            pages(items = listOf(100, 2000, 100)) { height -> Box(Modifier.fillMaxWidth().height(height.dp)) }
        }

        assertIs<PdfResult.Error.RenderingFailed>(result, "Expected an oversized item to fail, got $result")
        assertTrue(result.message.contains("Item 2"), "Error should name the item: ${result.message}")
        assertFalse(outputExists("contract-item-too-tall.pdf"))
    }

    @Test
    fun readPdfBytesReturnsTheGeneratedPdf() = runPdfTest {
        val result = generateSuccessfully("contract-bytes.pdf") {
            page { MarkerPage(PageMarkerColors[0]) }
            page { MarkerPage(PageMarkerColors[1]) }
        }

        val bytes = readPdfBytes(result.uri)

        assertEquals(result.fileSize, bytes.size.toLong(), "Byte count should match fileSize")
        assertEquals("%PDF-", bytes.copyOfRange(0, 5).decodeToString())
        assertEquals(2, pageCountOf(bytes), "The bytes should open as a 2-page PDF")
    }

    @Test
    fun readPdfBytesFailsForAMissingPdf() = runPdfTest {
        val outcome = runCatching { readPdfBytes(missingPdfUri) }

        assertTrue(outcome.isFailure, "Reading a missing PDF should fail, but returned ${outcome.getOrNull()?.size} bytes")
    }

    @Test
    fun metadataIsWrittenToThePdf() = runPdfTest {
        val metadata = PdfMetadata(
            title = "Quarterly report – Q3 ✓ résumé",
            author = "Jane (Finance) Doe \\ Co",
            subject = "Revenue",
            keywords = "finance, q3",
            creator = "KmPDF contract tests"
        )
        val result = createGenerator().generatePdf(config("contract-metadata.pdf").copy(metadata = metadata)) {
            page { MarkerPage(PageMarkerColors[0]) }
            page { MarkerPage(PageMarkerColors[1]) }
        }
        assertIs<PdfResult.Success>(result, "Expected a PDF with metadata, got $result")

        val info = readMetadata(result)
        assertEquals(metadata.title, info["Title"])
        assertEquals(metadata.author, info["Author"])
        assertEquals(metadata.subject, info["Subject"])
        assertEquals(metadata.keywords, info["Keywords"])
        assertEquals(metadata.creator, info["Creator"])
        if (writesProducer) assertEquals("KmPDF $KMPDF_VERSION", info["Producer"])
        assertEquals(2, readBack(result, renderPages = false).pageSizesPt.size, "The PDF should still open with both pages")
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
