package io.github.bigboyapps.kmpdf

import kotlin.math.roundToInt

/**
 * Minimal PDF writer that places one full-page RGB raster image on each page.
 *
 * Used by targets without a native PDF API (WASM). Image data is passed in already encoded
 * so each platform can use its own compressor.
 */
internal class RasterPdfWriter(
    private val widthPt: Float,
    private val heightPt: Float
) {
    /**
     * A page image of 8-bit RGB samples, row by row from the top-left.
     *
     * @property data Raw RGB bytes, or zlib-compressed RGB bytes when [flateCompressed] is true.
     */
    class PageImage(
        val widthPx: Int,
        val heightPx: Int,
        val data: ByteArray,
        val flateCompressed: Boolean
    ) {
        init {
            require(widthPx > 0 && heightPx > 0) { "Image dimensions must be positive" }
            require(flateCompressed || data.size == widthPx * heightPx * 3) {
                "Uncompressed image data must contain exactly width * height * 3 bytes"
            }
        }
    }

    private val pages = mutableListOf<PageImage>()

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

        // Object layout: 1 = catalog, 2 = page tree, then (page, content stream, image) per page
        val width = formatNumber(widthPt)
        val height = formatNumber(heightPt)
        val kids = pages.indices.joinToString(" ") { "${pageObjectNumber(it)} 0 R" }

        writeObject(1, "<< /Type /Catalog /Pages 2 0 R >>")
        writeObject(2, "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")

        pages.forEachIndexed { index, image ->
            val pageNumber = pageObjectNumber(index)
            val contentNumber = pageNumber + 1
            val imageNumber = pageNumber + 2

            writeObject(
                pageNumber,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                    "/Resources << /XObject << /Im0 $imageNumber 0 R >> >> /Contents $contentNumber 0 R >>"
            )

            // Scale the unit-square image to cover the whole page
            writeStream(contentNumber, "", "q $width 0 0 $height 0 0 cm /Im0 Do Q".encodeToByteArray())

            val filter = if (image.flateCompressed) " /Filter /FlateDecode" else ""
            writeStream(
                imageNumber,
                "/Type /XObject /Subtype /Image /Width ${image.widthPx} /Height ${image.heightPx} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8$filter",
                image.data
            )
        }

        val xrefOffset = out.size
        out.writeAscii("xref\n0 ${objectOffsets.size + 1}\n")
        out.writeAscii("0000000000 65535 f \n")
        objectOffsets.forEach { offset ->
            out.writeAscii("${offset.toString().padStart(10, '0')} 00000 n \n")
        }
        out.writeAscii("trailer\n<< /Size ${objectOffsets.size + 1} /Root 1 0 R >>\n")
        out.writeAscii("startxref\n$xrefOffset\n%%EOF\n")

        return out.toByteArray()
    }

    private fun pageObjectNumber(pageIndex: Int): Int = 3 + pageIndex * 3

    private fun formatNumber(value: Float): String {
        val hundredths = (value * 100).roundToInt()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        return if (fraction == 0) "$whole" else "$whole.${fraction.toString().padStart(2, '0').trimEnd('0')}"
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
