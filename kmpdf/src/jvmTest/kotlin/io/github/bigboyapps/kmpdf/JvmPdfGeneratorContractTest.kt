package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import io.github.bigboyapps.kmpdf.testing.RgbaImage
import io.github.bigboyapps.kmpdf.testing.renderWithImageComposeScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.time.Duration.Companion.minutes

/** Runs the shared generator contract on Desktop, reading PDFs back with PDFBox. */
class JvmPdfGeneratorContractTest : PdfGeneratorContract() {
    private val outputDir = Files.createTempDirectory("kmpdf-contract").toFile()

    @AfterTest
    fun cleanUp() {
        outputDir.deleteRecursively()
    }

    override fun runPdfTest(block: suspend CoroutineScope.() -> Unit): TestResult =
        runTest(timeout = 5.minutes) { block() }

    override fun createGenerator(): KmPdfGenerator = DesktopKmPdfGenerator()

    override fun config(fileName: String, pageSize: PageSize): PdfConfig =
        PdfConfig(pageSize = pageSize, fileName = fileName, outputDirectory = outputDir.absolutePath)

    override suspend fun readBack(result: PdfResult.Success, renderPages: Boolean): ReadBackDocument =
        withContext(Dispatchers.IO) {
            Loader.loadPDF(File(result.filePath)).use { document ->
                val sizes = document.pages.map { it.mediaBox.width to it.mediaBox.height }
                val pages = if (renderPages) {
                    val renderer = PDFRenderer(document)
                    (0 until document.numberOfPages).map { index ->
                        val image = renderer.renderImageWithDPI(index, 72f * RENDER_SCALE, ImageType.RGB)
                        RgbaImage.fromArgb(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
                    }
                } else {
                    emptyList()
                }
                ReadBackDocument(sizes, pages)
            }
        }

    override suspend fun renderReference(content: @Composable () -> Unit, widthPx: Int, heightPx: Int): RgbaImage =
        withContext(Dispatchers.Main) {
            renderWithImageComposeScene(content, widthPx, heightPx, RENDER_SCALE.toFloat())
        }

    override fun outputExists(fileName: String): Boolean = File(outputDir, fileName).exists()

    override suspend fun pageCountOf(bytes: ByteArray): Int = Loader.loadPDF(bytes).use { it.numberOfPages }

    override suspend fun readMetadata(result: PdfResult.Success): Map<String, String> =
        Loader.loadPDF(File(result.filePath)).use { document ->
            val info = document.documentInformation
            mapOf(
                "Title" to info.title,
                "Author" to info.author,
                "Subject" to info.subject,
                "Keywords" to info.keywords,
                "Creator" to info.creator,
                "Producer" to info.producer
            ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        }

    override suspend fun extractText(result: PdfResult.Success): List<String> =
        Loader.loadPDF(File(result.filePath)).use { document ->
            (1..document.numberOfPages).map { page ->
                PDFTextStripper().apply {
                    startPage = page
                    endPage = page
                }.getText(document)
            }
        }
}
