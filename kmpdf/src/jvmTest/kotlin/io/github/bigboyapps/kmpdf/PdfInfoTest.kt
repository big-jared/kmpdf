package io.github.bigboyapps.kmpdf

import io.github.bigboyapps.kmpdf.testing.PdfStructure
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks the Info dictionary helpers against PDFBox, an independent PDF implementation. */
class PdfInfoTest {
    private val metadata = PdfMetadata(
        title = "Résumé – 日本 ✓",
        author = "Jane (Finance) Doe \\ Co",
        subject = "Revenue",
        keywords = "finance, q3",
        creator = "PdfInfoTest"
    )

    /** A PDF from PDFBox, which writes a classic cross-reference table like Android's PdfDocument. */
    private fun pdfBoxPdf(pages: Int): ByteArray = PDDocument().use { document ->
        repeat(pages) { document.addPage(PDPage(PDRectangle.A4)) }
        ByteArrayOutputStream().also { document.save(it, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION) }
            .toByteArray()
    }

    @Test
    fun appendedInfoIsReadByPdfBoxAndTheStrictReader() {
        val original = pdfBoxPdf(pages = 3)
        val updated = appendInfoDictionary(original, metadata.infoEntries())

        assertContentEquals(original, updated.copyOf(original.size), "The update is appended without changing the original bytes")
        Loader.loadPDF(updated).use { document ->
            assertEquals(3, document.numberOfPages)
            val info = document.documentInformation
            assertEquals(metadata.title, info.title)
            assertEquals(metadata.author, info.author)
            assertEquals(metadata.subject, info.subject)
            assertEquals(metadata.keywords, info.keywords)
            assertEquals(metadata.creator, info.creator)
            assertEquals(KMPDF_PRODUCER, info.producer)
        }
        val structure = PdfStructure.parse(updated)
        assertEquals(3, structure.pages.size)
        assertEquals(metadata.title, structure.info["Title"])
        assertEquals(metadata.author, structure.info["Author"])
    }

    @Test
    fun infoCanBeAppendedTwice() {
        val once = appendInfoDictionary(pdfBoxPdf(pages = 1), PdfMetadata(title = "First").infoEntries())
        val twice = appendInfoDictionary(once, PdfMetadata(title = "Second").infoEntries())

        Loader.loadPDF(twice).use { assertEquals("Second", it.documentInformation.title) }
        assertEquals("Second", PdfStructure.parse(twice).info["Title"])
    }

    @Test
    fun rasterWriterInfoIsReadable() {
        val writer = RasterPdfWriter()
        writer.addPage(RasterPdfWriter.PageImage(595f, 842f, 1, 1, byteArrayOf(0, 0, 0), flateCompressed = false))
        writer.info = metadata.infoEntries()
        val pdf = writer.build()

        Loader.loadPDF(pdf).use { document ->
            assertEquals(metadata.title, document.documentInformation.title)
            assertEquals(KMPDF_PRODUCER, document.documentInformation.producer)
        }
        assertEquals(metadata.author, PdfStructure.parse(pdf).info["Author"])
    }

    @Test
    fun onlyTheProducerIsWrittenWithoutMetadata() {
        assertEquals(listOf("Producer" to "KmPDF $KMPDF_VERSION"), PdfMetadata().infoEntries())
    }

    @Test
    fun rejectsFilesItCantUpdate() {
        assertFailsWith<IllegalArgumentException> { appendInfoDictionary("not a pdf".encodeToByteArray(), metadata.infoEntries()) }
    }
}
