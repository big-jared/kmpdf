package io.github.bigboyapps.kmpdf.testing

import io.github.bigboyapps.kmpdf.RasterPdfWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Makes sure the strict test reader accepts valid files and rejects broken ones, since other tests rely on it. */
class PdfStructureTest {
    private val pixels = ByteArray(2 * 3 * 3) { it.toByte() }

    private fun validPdf(): ByteArray {
        val writer = RasterPdfWriter()
        writer.addPage(RasterPdfWriter.PageImage(612.5f, 792.25f, 2, 3, pixels, flateCompressed = false))
        writer.addPage(RasterPdfWriter.PageImage(612.5f, 792.25f, 2, 3, pixels, flateCompressed = false))
        return writer.build()
    }

    @Test
    fun readsPagesSizesAndImages() {
        val structure = PdfStructure.parse(validPdf())

        assertEquals(2, structure.pages.size)
        structure.pages.forEach { page ->
            assertEquals(612.5f, page.widthPt)
            assertEquals(792.25f, page.heightPt)
        }
        val image = structure.images(1).single()
        assertEquals(2, image.width)
        assertEquals(3, image.height)
        assertEquals("DeviceRGB", image.colorSpace)
        assertTrue(image.filters.isEmpty())
        assertContentEquals(pixels, image.data)
    }

    @Test
    fun rejectsCrossReferenceEntryPointingAtWrongOffset() {
        val bytes = validPdf()
        val text = bytes.decodeLatin1()
        val entry = Regex("""(\d{10}) 00000 n """).findAll(text).last()
        val shifted = (entry.groupValues[1].toInt() + 1).toString().padStart(10, '0')
        val corrupted = text.replaceRange(entry.groups[1]!!.range, shifted)

        assertFails { PdfStructure.parse(corrupted.encodeLatin1()) }
    }

    @Test
    fun rejectsMissingEofMarker() {
        val text = validPdf().decodeLatin1()
        assertFails { PdfStructure.parse(text.substringBeforeLast("%%EOF").encodeLatin1()).pages }
    }

    @Test
    fun rejectsWrongStreamLength() {
        val text = validPdf().decodeLatin1()
        val corrupted = text.replace("/Length ${pixels.size} ", "/Length ${pixels.size - 1} ")

        assertFails { PdfStructure.parse(corrupted.encodeLatin1()).images(0) }
    }

    @Test
    fun rejectsPageCountMismatch() {
        val text = validPdf().decodeLatin1()
        // Keep the byte length identical so cross-reference offsets stay valid
        val corrupted = text.replace("/Count 2 ", "/Count 3 ")

        assertFails { PdfStructure.parse(corrupted.encodeLatin1()).pages }
    }

    private fun ByteArray.decodeLatin1(): String = buildString { this@decodeLatin1.forEach { append((it.toInt() and 0xFF).toChar()) } }

    private fun String.encodeLatin1(): ByteArray = ByteArray(length) { this[it].code.toByte() }
}
