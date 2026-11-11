package io.github.bigboyapps.kmpdf

import io.github.bigboyapps.kmpdf.testing.PdfStructure
import org.apache.pdfbox.Loader
import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks the Info dictionary against PDFBox, an independent PDF implementation. */
class PdfInfoTest {
    private val metadata = PdfMetadata(
        title = "Résumé – 日本 ✓",
        author = "Jane (Finance) Doe \\ Co",
        subject = "Revenue",
        keywords = "finance, q3",
        creator = "PdfInfoTest"
    )

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
}
