package io.github.bigboyapps.kmpdf.sample

import io.github.bigboyapps.kmpdf.DesktopKmPdfGenerator
import io.github.bigboyapps.kmpdf.PageSize
import io.github.bigboyapps.kmpdf.PdfMargins
import io.github.bigboyapps.kmpdf.PdfResult
import io.github.bigboyapps.kmpdf.readPdfBytes
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Generates every sample the app offers and checks the PDF that comes out, so the samples can't quietly
 * break. Each PDF is read back with PDFBox, an independent PDF implementation.
 */
class SampleDocumentsTest {
    private val outputDir = Files.createTempDirectory("kmpdf-sample").toFile()

    @AfterTest
    fun cleanUp() {
        outputDir.deleteRecursively()
    }

    private suspend fun generate(
        type: SampleType,
        pageSize: PageSize = PageSize.A4,
        margins: PdfMargins = PdfMargins.None
    ): PdfResult = DesktopKmPdfGenerator().generatePdf(
        config = sampleConfig(type, pageSize, margins, outputDirectory = outputDir.absolutePath)
    ) {
        sampleDocument(type)
    }

    private suspend fun generateSuccessfully(
        type: SampleType,
        pageSize: PageSize = PageSize.A4,
        margins: PdfMargins = PdfMargins.None
    ): PdfResult.Success {
        val result = generate(type, pageSize, margins)
        assertIs<PdfResult.Success>(result, "${type.title} should generate, got $result")
        return result
    }

    private fun <T> PdfResult.Success.readBack(block: (PDDocument) -> T): T =
        Loader.loadPDF(File(filePath)).use(block)

    private fun PdfResult.Success.text(): String = readBack { document ->
        PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
    }

    @Test
    fun everySampleGeneratesAReadablePdf() = runTest(timeout = 10.minutes) {
        SampleType.entries.filter { it != SampleType.NEVER_LOADS }.forEach { type ->
            val result = generateSuccessfully(type)

            assertTrue(result.pageCount >= 1, "${type.title} should have pages")
            val bytes = readPdfBytes(result.uri)
            assertEquals(result.fileSize, bytes.size.toLong(), "${type.title}: readPdfBytes should return the whole file")
            assertEquals("%PDF-", bytes.copyOfRange(0, 5).decodeToString(), "${type.title} should be a PDF")

            result.readBack { document ->
                assertEquals(result.pageCount, document.numberOfPages, "${type.title} page count")
                assertEquals(
                    "KmPDF sample: ${type.title} – résumé ✓",
                    document.documentInformation.title,
                    "${type.title} metadata should round-trip"
                )
                // Every page must render, which fails loudly on a malformed page
                val renderer = PDFRenderer(document)
                repeat(document.numberOfPages) { page ->
                    val image = renderer.renderImage(page, 0.3f)
                    assertTrue(image.width > 0 && image.height > 0, "${type.title} page ${page + 1} should render")
                }
            }
        }
    }

    @Test
    fun selectableTextSampleCanBeSearched() = runTest(timeout = 5.minutes) {
        val text = generateSuccessfully(SampleType.SELECTABLE_TEXT).text()

        listOf(
            "Invoice #1042 (paid)",
            "Résumé, naïve café, déjà vu, Ærøskøbing",
            "日本語のテキスト",
            "한국어 텍스트",
            "🎉",
            "The quick brown fox jumps over the lazy dog",
            "Subtotal (indented with spaces)",
            "Total due: $1,284.50",
            "Thank you for your business",
            "Tagged and padded text",
            "Visible line",
            "Rotated 8 degrees"
        ).forEach { phrase ->
            assertTrue(phrase in text, "The selectable text sample should contain \"$phrase\", got: $text")
        }

        listOf("HIDDENBYCLIP", "HIDDENTAIL").forEach { hidden ->
            assertTrue(hidden !in text, "\"$hidden\" is hidden on the page and shouldn't be in the PDF")
        }
    }

    @Test
    fun asyncSampleWaitsForItsContent() = runTest(timeout = 5.minutes) {
        val text = generateSuccessfully(SampleType.ASYNC_CONTENT).text()

        assertTrue("Loaded before capture" in text, "The page should be captured after its data arrives, got: $text")
        listOf("North", "South", "East", "West").forEach { region ->
            assertTrue(region in text, "The loaded rows should be in the PDF, got: $text")
        }
        assertTrue("Loading" !in text, "The loading placeholder shouldn't be captured, got: $text")
    }

    @Test
    fun contentThatNeverLoadsFailsInsteadOfHanging() = runTest(timeout = 5.minutes) {
        val result = generate(SampleType.NEVER_LOADS)

        val error = assertIs<PdfResult.Error.RenderingFailed>(result, "Expected a rendering error, got $result")
        assertTrue("PdfContentLoading" in error.message, "The error should explain the timeout, got: ${error.message}")
        assertTrue(
            !File(outputDir, "sample_never_loads.pdf").exists(),
            "A failed generation shouldn't leave a PDF behind"
        )
    }

    @Test
    fun invoiceSampleFlowsAcrossPagesWithPageNumbers() = runTest(timeout = 5.minutes) {
        val result = generateSuccessfully(SampleType.PAGINATED_INVOICE, PageSize.Letter, PdfMargins.Narrow)
        val text = result.text()

        assertTrue(result.pageCount > 1, "100 invoice rows should need several pages")
        (1..result.pageCount).forEach { page ->
            assertTrue("Page $page of ${result.pageCount}" in text, "Page $page should have its footer, got: $text")
        }
        // Every one of the 100 rows should be on some page: the row names repeat in a cycle of five
        val rowsInPdf = sampleInvoiceRows.map { it.item }.distinct().sumOf { name ->
            Regex(Regex.escape(name)).findAll(text).count()
        }
        assertEquals(sampleInvoiceRows.size, rowsInPdf, "Every invoice row should be in the PDF")
    }

    @Test
    fun receiptSampleIsAsTallAsItsContent() = runTest(timeout = 5.minutes) {
        val result = generateSuccessfully(SampleType.RECEIPT, margins = PdfMargins.all(12.dp()))

        result.readBack { document ->
            assertEquals(1, document.numberOfPages)
            val box = document.getPage(0).mediaBox
            // wrapHeight sets the whole page width; margins are inside it
            assertEquals(280f, box.width, 0.01f, "The page should be as wide as asked")
            assertTrue(box.height in 100f..500f, "The page should be as tall as the receipt, was ${box.height}")
        }
        val text = result.text()
        val total = sampleReceiptLines.sumOf { it.priceCents }
        val expectedTotal = "Total $${total / 100}.${(total % 100).toString().padStart(2, '0')}"
        assertTrue("KmPDF Coffee" in text && expectedTotal in text, "The receipt should be selectable, got: $text")
    }

    @Test
    fun mixedSizesSampleKeepsEveryPageSize() = runTest(timeout = 5.minutes) {
        val result = generateSuccessfully(SampleType.MIXED_SIZES)

        result.readBack { document ->
            val sizes = (0 until document.numberOfPages).map { document.getPage(it).mediaBox.width to document.getPage(it).mediaBox.height }
            assertEquals(4, sizes.size)
            assertEquals(595f to 842f, sizes[0].round(), "A4")
            assertEquals(792f to 612f, sizes[1].round(), "Letter landscape")
            assertTrue(
                abs(sizes[2].first - 612.5f) < 0.01f && abs(sizes[2].second - 400.25f) < 0.01f,
                "The fractional size should be kept exactly, was ${sizes[2]}"
            )
            assertEquals(400f, sizes[3].first, 0.01f, "The content-sized page should be as wide as asked")
            assertTrue(sizes[3].second < 300f, "The content-sized page should be short, was ${sizes[3].second}")
        }
    }

    private fun Pair<Float, Float>.round() = kotlin.math.round(first) to kotlin.math.round(second)

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
}
