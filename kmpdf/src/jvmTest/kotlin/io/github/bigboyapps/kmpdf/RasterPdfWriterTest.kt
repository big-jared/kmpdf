package io.github.bigboyapps.kmpdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RasterPdfWriterTest {
    private val width = 4
    private val height = 3

    /** Red on the top row, blue everywhere else. */
    private val pixels = ByteArray(width * height * 3) { i ->
        val pixel = i / 3
        val channel = i % 3
        val isTopRow = pixel < width
        when {
            isTopRow && channel == 0 -> 0xFF.toByte()
            !isTopRow && channel == 2 -> 0xFF.toByte()
            else -> 0
        }
    }

    @Test
    fun writesCompressedAndUncompressedPagesThatPdfBoxCanRead() {
        val writer = RasterPdfWriter()
        writer.addPage(RasterPdfWriter.PageImage(595f, 842f, width, height, zlib(pixels), flateCompressed = true))
        writer.addPage(RasterPdfWriter.PageImage(595f, 842f, width, height, pixels, flateCompressed = false))

        Loader.loadPDF(writer.build()).use { document ->
            assertEquals(2, document.numberOfPages)
            document.pages.forEach { page ->
                assertEquals(595f, page.mediaBox.width)
                assertEquals(842f, page.mediaBox.height)

                val name = page.resources.xObjectNames.single()
                val image = (page.resources.getXObject(name) as PDImageXObject).image
                assertEquals(width, image.width)
                assertEquals(height, image.height)
                assertEquals(0xFFFF0000.toInt(), image.getRGB(0, 0))
                assertEquals(0xFF0000FF.toInt(), image.getRGB(3, 2))
            }
        }
    }

    @Test
    fun writesExactCrossReferenceOffsets() {
        val writer = RasterPdfWriter()
        writer.addPage(RasterPdfWriter.PageImage(612f, 792f, width, height, pixels, flateCompressed = false))
        val bytes = writer.build()
        // PDFBox repairs broken xref tables silently, so check the offsets directly
        val text = String(bytes, Charsets.ISO_8859_1)

        val xrefOffset = text.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertTrue(text.startsWith("xref\n", xrefOffset))

        val entries = text.substring(xrefOffset).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(5, entries.size)
        entries.forEachIndexed { index, entry ->
            assertEquals(20, entry.length + 1, "xref entries must be exactly 20 bytes")
            val offset = entry.substring(0, 10).toInt()
            assertTrue(text.startsWith("${index + 1} 0 obj\n", offset), "Wrong offset for object ${index + 1}")
        }
    }

    @Test
    fun writesFractionalPageSizes() {
        val writer = RasterPdfWriter()
        writer.addPage(RasterPdfWriter.PageImage(612.5f, 792.25f, width, height, pixels, flateCompressed = false))

        Loader.loadPDF(writer.build()).use { document ->
            assertEquals(612.5f, document.getPage(0).mediaBox.width)
            assertEquals(792.25f, document.getPage(0).mediaBox.height)
        }
    }

    @Test
    fun eachPageKeepsItsOwnSize() {
        val writer = RasterPdfWriter()
        writer.addPage(RasterPdfWriter.PageImage(595f, 842f, width, height, pixels, flateCompressed = false))
        writer.addPage(RasterPdfWriter.PageImage(400f, 123.5f, width, height, pixels, flateCompressed = false))

        Loader.loadPDF(writer.build()).use { document ->
            assertEquals(595f, document.getPage(0).mediaBox.width)
            assertEquals(842f, document.getPage(0).mediaBox.height)
            assertEquals(400f, document.getPage(1).mediaBox.width)
            assertEquals(123.5f, document.getPage(1).mediaBox.height)
        }
    }

    @Test
    fun rejectsDocumentWithoutPages() {
        assertFailsWith<IllegalArgumentException> {
            RasterPdfWriter().build()
        }
    }

    @Test
    fun rejectsUncompressedDataOfWrongSize() {
        assertFailsWith<IllegalArgumentException> {
            RasterPdfWriter.PageImage(595f, 842f, width, height, ByteArray(5), flateCompressed = false)
        }
    }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }
}
