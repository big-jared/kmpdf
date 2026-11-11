package io.github.bigboyapps.kmpdf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bigboyapps.kmpdf.testing.PdfStructure
import io.github.bigboyapps.kmpdf.testing.Text
import io.github.bigboyapps.kmpdf.testing.TextSamples
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Writes a corpus of PDFs covering every feature and checks them the same way every time: byte for byte
 * across runs, with the strict structure reader, and with PDFBox.
 *
 * The corpus is left in `build/validation-pdfs` so external validators can check it too. CI runs
 * `qpdf --check` over the directory, which is an independent check of the file structure.
 */
class PdfConformanceTest {
    private val corpusDir = File("build/validation-pdfs").apply { mkdirs() }

    private suspend fun generate(name: String, config: PdfConfig, pages: PdfPageScope.() -> Unit): ByteArray {
        val result = DesktopKmPdfGenerator().generatePdf(
            config.copy(fileName = "$name.pdf", outputDirectory = corpusDir.absolutePath),
            pages
        )
        assertIs<PdfResult.Success>(result, "$name should generate, got $result")
        return File(result.filePath).readBytes()
    }

    /** One document per feature: text, pagination, page sizes, margins, metadata, and graphics. */
    private suspend fun corpus(): Map<String, ByteArray> = buildMap {
        put(
            "text",
            generate("text", PdfConfig(fileName = "x", margins = PdfMargins.Narrow, metadata = METADATA)) {
                page { TextCases() }
                page { Text("Second page", fontSize = 18.sp) }
            }
        )
        put(
            "paginated",
            generate("paginated", PdfConfig(pageSize = PageSize.Letter, fileName = "x", margins = PdfMargins.Normal)) {
                pages(
                    items = (1..40).toList(),
                    itemSpacing = 4.dp,
                    header = { Text("Report", fontSize = 16.sp) },
                    footer = { info -> Text("Page ${info.pageNumber} of ${info.pageCount}", fontSize = 9.sp) }
                ) { item ->
                    Box(Modifier.fillMaxWidth().height((18 + item % 5 * 9).dp).background(Color(0xFFDDE7F7))) {
                        Text("Row $item", Modifier.padding(4.dp), fontSize = 10.sp)
                    }
                }
            }
        )
        put(
            "page-sizes",
            generate("page-sizes", PdfConfig(fileName = "x", margins = PdfMargins.Wide)) {
                page { Text("A4", fontSize = 14.sp) }
                page(size = PageSize(width = 792.dp, height = 612.dp)) { Text("Letter landscape", fontSize = 14.sp) }
                page(size = PageSize(width = 612.5.dp, height = 400.25.dp)) { Text("Fractional", fontSize = 14.sp) }
                page(size = PageSize.wrapHeight(300.dp)) {
                    Box(Modifier.fillMaxWidth().height(120.dp).background(Color(0xFFB4D5A8)))
                }
            }
        )
        put(
            "graphics",
            generate("graphics", PdfConfig(fileName = "x")) {
                page {
                    Column(Modifier.fillMaxWidth().background(Color.White).padding(24.dp)) {
                        Box(Modifier.width(200.dp).height(80.dp).background(Color(0x8834A853)))
                        Box(Modifier.width(120.dp).height(120.dp).background(Color(0xFF1A73E8)))
                        Text("Label over graphics", fontSize = 12.sp)
                    }
                }
            }
        )
    }

    @Test
    fun corpusIsValidAndReproducible() = runTest(timeout = 5.minutes) {
        val first = corpus()
        val second = corpus()

        first.forEach { (name, bytes) ->
            // Byte-for-byte reproducible: the same document always produces the same file
            assertContentEquals(bytes, second.getValue(name), "$name should be byte-for-byte reproducible")

            // The strict reader validates the header, every cross-reference entry, and the page tree
            val structure = PdfStructure.parse(bytes)
            assertTrue(structure.pages.isNotEmpty(), "$name should have pages")
            structure.pages.indices.forEach { page ->
                val images = structure.images(page)
                assertEquals(1, images.size, "$name page ${page + 1} should have one page image")
                assertEquals("DeviceRGB", images.single().colorSpace)
                // Reading the runs also checks the font is a complete, embedded Identity-H font
                structure.textRuns(page).forEach { run ->
                    assertEquals(3, run.renderMode, "$name page ${page + 1} text should be invisible")
                }
            }

            // PDFBox is an independent implementation: it must open every page and render it
            Loader.loadPDF(bytes).use { document ->
                assertEquals(structure.pages.size, document.numberOfPages, "$name page count")
                val renderer = PDFRenderer(document)
                repeat(document.numberOfPages) { page ->
                    val image = renderer.renderImage(page, 0.4f)
                    assertTrue(image.width > 0 && image.height > 0, "$name page ${page + 1} should render")
                }
                assertEquals("KmPDF $KMPDF_VERSION", document.documentInformation.producer, "$name producer")
            }
        }
    }

    private companion object {
        val METADATA = PdfMetadata(
            title = "Conformance – résumé ✓",
            author = "Jane (Finance) Doe \\ Co",
            subject = "Validation corpus",
            keywords = "kmpdf, validation",
            creator = "PdfConformanceTest"
        )
    }
}

@Composable
private fun TextCases() {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
        TextSamples.phrases.forEach { phrase -> Text(phrase, Modifier.width(300.dp), fontSize = 12.sp) }
        Text("        Indented with spaces", fontSize = 12.sp)
        Text("🎉 emoji and math 𝛑", fontSize = 12.sp)
    }
}
