package io.github.bigboyapps.kmpdf

import io.github.bigboyapps.kmpdf.testing.PdfStructure
import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
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

    private fun line(text: String, left: Float, baseline: Float, size: Float = 12f, width: Float = text.length * size * 0.55f) =
        PageTextLine(text, left, baseline - size * 0.9f, width, size * 1.2f, baseline)

    private fun textPdf(vararg pages: List<PageTextLine>): ByteArray {
        val writer = RasterPdfWriter()
        pages.forEach { lines -> writer.addPage(RasterPdfWriter.PageImage(595f, 842f, width, height, pixels, false, lines)) }
        return writer.build()
    }

    @Test
    fun textLayerIsExtractedInAnyScript() {
        val lines = listOf(
            line("Invoice #1042 (paid) \\ total", 72f, 100f),
            line("Résumé – déjà vu ✓", 72f, 130f),
            line("日本語のテキスト 한국어", 72f, 160f),
            line("Emoji 🎉 and math 𝛑", 72f, 190f)
        )
        val pdf = textPdf(lines, emptyList(), listOf(line("Last page", 72f, 100f)))

        Loader.loadPDF(pdf).use { document ->
            fun pageText(page: Int) = PDFTextStripper().apply {
                startPage = page
                endPage = page
            }.getText(document)

            val first = pageText(1)
            lines.forEach { assertTrue(it.text in first, "\"${it.text}\" should be extracted, got \"$first\"") }
            assertEquals("", pageText(2).trim(), "A page without text lines has no text")
            assertEquals("Last page", pageText(3).trim())
        }
        val structure = PdfStructure.parse(pdf)
        assertEquals(lines.map { it.text }, structure.textRuns(0).map { it.text }, "The strict reader decodes the same text")
    }

    @Test
    fun charactersOutsideTheBasicPlaneShareCodesAcrossPages() {
        val pdf = textPdf(listOf(line("🎉 x 🎉", 72f, 100f)), listOf(line("𝛑 🎉", 72f, 100f)))
        val structure = PdfStructure.parse(pdf)

        assertEquals("🎉 x 🎉", structure.textRuns(0).single().text)
        assertEquals("𝛑 🎉", structure.textRuns(1).single().text)
        // Each character is one glyph, so selection highlights line up with what's drawn
        assertEquals(5, structure.textRuns(0).single().text.codePointCount())
    }

    @Test
    fun loneSurrogatesBecomeReplacementCharacters() {
        val pdf = textPdf(listOf(line("a\uD83Db", 72f, 100f)))

        assertEquals("a\uFFFDb", PdfStructure.parse(pdf).textRuns(0).single().text)
    }

    private fun String.codePointCount() = codePointCount(0, length)

    @Test
    fun textLayerIsInvisibleAndPositioned() {
        val pdf = textPdf(listOf(line("Hello", left = 72f, baseline = 100f, size = 20f, width = 50f)))
        val structure = PdfStructure.parse(pdf)
        val run = structure.textRuns(0).single()

        assertEquals("Hello", run.text)
        assertEquals(3, run.renderMode)
        assertEquals(72f, run.x, 0.01f)
        assertEquals(842f - 100f, run.y, 0.01f)
        assertEquals(50f, run.width, 0.05f)

        // PDFBox agrees on where each character is
        Loader.loadPDF(pdf).use { document ->
            val positions = mutableListOf<TextPosition>()
            object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    positions += textPositions
                }
            }.getText(document)
            assertEquals("Hello", positions.joinToString("") { it.unicode })
            assertEquals(72f, positions.first().xDirAdj, 0.05f)
            assertEquals(122f, positions.last().xDirAdj + positions.last().widthDirAdj, 0.05f)
            assertEquals(100f, positions.first().yDirAdj, 0.05f)
        }
    }

    @Test
    fun embeddedFontIsAValidTrueTypeFont() {
        val font = TTFParser().parse(RandomAccessReadBuffer(InvisibleTextFont.fontProgram))
        font.use {
            assertEquals(2, it.numberOfGlyphs)
            assertEquals(1000, it.unitsPerEm)
            assertEquals(InvisibleTextFont.GLYPH_WIDTH, it.horizontalMetrics.getAdvanceWidth(1))
            assertEquals(0, it.glyph.getGlyph(1)?.numberOfContours ?: 0)
        }

        // Every table checksum, and the whole-font checksum, must be right for strict font loaders
        val bytes = InvisibleTextFont.fontProgram
        fun u32(offset: Int) = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[offset + i].toLong() and 0xFF) }
        fun checksum(from: Int, length: Int): Long {
            var sum = 0L
            for (offset in from until from + length step 4) {
                var word = 0L
                for (i in 0 until 4) word = (word shl 8) or (if (offset + i < from + length) bytes[offset + i].toLong() and 0xFF else 0)
                sum = (sum + word) and 0xFFFFFFFFL
            }
            return sum
        }
        val tableCount = (u32(4) shr 16).toInt()
        for (table in 0 until tableCount) {
            val entry = 12 + table * 16
            val tag = String(bytes, entry, 4, Charsets.US_ASCII)
            val expected = u32(entry + 4)
            var actual = checksum(u32(entry + 8).toInt(), u32(entry + 12).toInt())
            if (tag == "head") actual = (actual - u32(u32(entry + 8).toInt() + 8)) and 0xFFFFFFFFL
            assertEquals(expected, actual, "Checksum of the $tag table")
        }
        assertEquals(0xB1B0AFBAL, checksum(0, bytes.size), "Whole-font checksum")
    }

    @Test
    fun textLinesWithoutSizeAreSkipped() {
        val pdf = textPdf(listOf(PageTextLine("Empty width", 0f, 0f, 0f, 12f, 10f), PageTextLine("", 0f, 0f, 10f, 12f, 10f)))

        assertTrue(PdfStructure.parse(pdf).textRuns(0).isEmpty())
        assertTrue("/Font" !in pdf.decodeToString(), "No font is written when no text is")
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
