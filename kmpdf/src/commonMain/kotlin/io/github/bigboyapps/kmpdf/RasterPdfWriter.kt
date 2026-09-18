package io.github.bigboyapps.kmpdf

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes PDFs where each page is a full-page RGB image with an invisible text layer over it.
 *
 * Every platform renders pages to pixels and collects their text lines; this writes the same PDF from them
 * everywhere. Each page can have its own size. Image data is passed in already encoded so each platform can
 * use its own compressor.
 */
internal class RasterPdfWriter {
    /**
     * A page, its image of 8-bit RGB samples (row by row from the top-left), and its text.
     *
     * @property widthPt The page width in points.
     * @property heightPt The page height in points.
     * @property data Raw RGB bytes, or zlib-compressed RGB bytes when [flateCompressed] is true.
     * @property textLines Text written invisibly over the image so it can be selected and searched.
     */
    class PageImage(
        val widthPt: Float,
        val heightPt: Float,
        val widthPx: Int,
        val heightPx: Int,
        val data: ByteArray,
        val flateCompressed: Boolean,
        textLines: List<PageTextLine> = emptyList()
    ) {
        /** The lines that can be written: ones with text and a size. */
        val textLines: List<PageTextLine> = textLines.filter { it.text.isNotEmpty() && it.width > 0f && it.height > 0f }

        init {
            require(widthPt > 0f && heightPt > 0f) { "Page size must be positive, got $widthPt x $heightPt pt" }
            require(widthPx > 0 && heightPx > 0) { "Image dimensions must be positive" }
            require(flateCompressed || data.size == widthPx * heightPx * 3) {
                "Uncompressed image data must contain exactly width * height * 3 bytes"
            }
        }
    }

    private val pages = mutableListOf<PageImage>()

    /** Info dictionary entries, as key and text value pairs, written when not empty. */
    var info: List<Pair<String, String>> = emptyList()

    val pageCount: Int get() = pages.size

    fun addPage(image: PageImage) {
        pages.add(image)
    }

    fun build(): ByteArray {
        require(pages.isNotEmpty()) { "PDF must contain at least one page" }

        val out = ByteSink()
        val objectOffsets = mutableListOf<Int>()

        fun beginObject(number: Int) {
            check(number == objectOffsets.size + 1) { "Objects must be written in order" }
            objectOffsets.add(out.size)
            out.writeAscii("$number 0 obj\n")
        }

        fun writeObject(number: Int, dictionary: String) {
            beginObject(number)
            out.writeAscii("$dictionary\nendobj\n")
        }

        fun writeStream(number: Int, dictionaryEntries: String, data: ByteArray) {
            beginObject(number)
            out.writeAscii("<< $dictionaryEntries /Length ${data.size} >>\nstream\n")
            out.writeBytes(data)
            out.writeAscii("\nendstream\nendobj\n")
        }

        out.writeAscii("%PDF-1.4\n")
        // Binary comment so transfer tools treat the file as binary
        out.writeBytes(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        // Object layout: 1 = catalog, 2 = page tree, (page, content stream, image) per page, then the text
        // layer font's five objects, then the Info dictionary
        val kids = pages.indices.joinToString(" ") { "${pageObjectNumber(it)} 0 R" }
        val hasText = pages.any { it.textLines.isNotEmpty() }
        val fontNumber = pageObjectNumber(pages.size)
        val encoder = InvisibleTextFont.TextEncoder()

        writeObject(1, "<< /Type /Catalog /Pages 2 0 R >>")
        writeObject(2, "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")

        pages.forEachIndexed { index, image ->
            val pageNumber = pageObjectNumber(index)
            val contentNumber = pageNumber + 1
            val imageNumber = pageNumber + 2
            val width = formatNumber(image.widthPt)
            val height = formatNumber(image.heightPt)
            val fontResource = if (image.textLines.isNotEmpty()) " /Font << /F1 $fontNumber 0 R >>" else ""

            writeObject(
                pageNumber,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                    "/Resources << /XObject << /Im0 $imageNumber 0 R >>$fontResource >> /Contents $contentNumber 0 R >>"
            )

            // Scale the unit-square image to cover the whole page, then add the invisible text over it
            val content = "q $width 0 0 $height 0 0 cm /Im0 Do Q" + textLayer(image.textLines, image.heightPt, encoder)
            writeStream(contentNumber, "", content.encodeToByteArray())

            val filter = if (image.flateCompressed) " /Filter /FlateDecode" else ""
            writeStream(
                imageNumber,
                "/Type /XObject /Subtype /Image /Width ${image.widthPx} /Height ${image.heightPx} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8$filter",
                image.data
            )
        }

        if (hasText) {
            val font = InvisibleTextFont
            val cidFontNumber = fontNumber + 1
            val descriptorNumber = fontNumber + 2
            val fontFileNumber = fontNumber + 3
            val toUnicodeNumber = fontNumber + 4
            writeObject(
                fontNumber,
                "<< /Type /Font /Subtype /Type0 /BaseFont /${font.NAME} /Encoding /Identity-H " +
                    "/DescendantFonts [$cidFontNumber 0 R] /ToUnicode $toUnicodeNumber 0 R >>"
            )
            writeObject(
                cidFontNumber,
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /${font.NAME} " +
                    "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                    "/FontDescriptor $descriptorNumber 0 R /DW ${font.GLYPH_WIDTH} /CIDToGIDMap /Identity >>"
            )
            writeObject(
                descriptorNumber,
                "<< /Type /FontDescriptor /FontName /${font.NAME} /Flags 4 " +
                    "/FontBBox [0 ${font.DESCENT} ${font.GLYPH_WIDTH} ${font.ASCENT}] /ItalicAngle 0 " +
                    "/Ascent ${font.ASCENT} /Descent ${font.DESCENT} /CapHeight ${font.ASCENT} /StemV 80 " +
                    "/FontFile2 $fontFileNumber 0 R >>"
            )
            writeStream(fontFileNumber, "/Length1 ${font.fontProgram.size}", font.fontProgram)
            writeStream(toUnicodeNumber, "", font.toUnicodeCMap(encoder.supplementary).encodeToByteArray())
        }

        val infoReference = if (info.isNotEmpty()) {
            val infoNumber = objectOffsets.size + 1
            writeObject(infoNumber, "<< ${info.joinToString(" ") { (key, value) -> "/$key ${pdfTextString(value)}" }} >>")
            " /Info $infoNumber 0 R"
        } else {
            ""
        }

        val xrefOffset = out.size
        out.writeAscii("xref\n0 ${objectOffsets.size + 1}\n")
        out.writeAscii("0000000000 65535 f \n")
        objectOffsets.forEach { offset ->
            out.writeAscii("${offset.toString().padStart(10, '0')} 00000 n \n")
        }
        out.writeAscii("trailer\n<< /Size ${objectOffsets.size + 1} /Root 1 0 R$infoReference >>\n")
        out.writeAscii("startxref\n$xrefOffset\n%%EOF\n")

        return out.toByteArray()
    }

    /**
     * Invisible text (render mode 3) for each line, placed on its baseline. Each line uses its height as the
     * font size and is stretched horizontally so its width matches the rendered line.
     */
    private fun textLayer(lines: List<PageTextLine>, pageHeightPt: Float, encoder: InvisibleTextFont.TextEncoder): String {
        if (lines.isEmpty()) return ""
        return buildString {
            append("\nBT\n3 Tr\n")
            lines.forEach { line ->
                val codes = encoder.encode(line.text)
                val fontSize = line.height
                val naturalWidth = codes.size * InvisibleTextFont.GLYPH_WIDTH / 1000f * fontSize
                val horizontalScale = line.width / naturalWidth * 100f
                append("/F1 ${formatNumber(fontSize)} Tf ${formatNumber(horizontalScale)} Tz ")
                append("1 0 0 1 ${formatNumber(line.left)} ${formatNumber(pageHeightPt - line.baseline)} Tm <")
                codes.forEach { append(it.toString(16).uppercase().padStart(4, '0')) }
                append("> Tj\n")
            }
            append("ET")
        }
    }

    private fun pageObjectNumber(pageIndex: Int): Int = 3 + pageIndex * 3

    private fun formatNumber(value: Float): String {
        val hundredths = (abs(value) * 100).roundToInt()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        val sign = if (value < 0f && hundredths != 0) "-" else ""
        return if (fraction == 0) "$sign$whole" else "$sign$whole.${fraction.toString().padStart(2, '0').trimEnd('0')}"
    }

    private class ByteSink {
        private var buffer = ByteArray(64 * 1024)
        var size = 0
            private set

        fun writeAscii(text: String) {
            writeBytes(text.encodeToByteArray())
        }

        fun writeBytes(bytes: ByteArray) {
            ensureCapacity(size + bytes.size)
            bytes.copyInto(buffer, destinationOffset = size)
            size += bytes.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= buffer.size) return
            var newSize = buffer.size * 2
            while (newSize < required) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }
    }
}
