package io.github.bigboyapps.kmpdf

/**
 * The font used for the invisible text layer.
 *
 * Invisible text is never drawn, so the font has no real glyphs. It's a composite font (Type0) that
 * encodes each UTF-16 code unit directly as a glyph ID, with a ToUnicode map that maps every code back to
 * itself, so any language can be selected, searched, and copied. Every glyph has the same width, and each
 * line is stretched horizontally to match the rendered text's width.
 */
internal object InvisibleTextFont {
    const val NAME = "KmPdfInvisibleText"

    /** Every glyph's advance width, in thousandths of the font size. */
    const val GLYPH_WIDTH = 500

    /** Ascent and descent, in thousandths of the font size. Viewers use them to size selection highlights. */
    const val ASCENT = 800
    const val DESCENT = -200

    /** A minimal, valid TrueType program with no visible glyphs, embedded so viewers never substitute a font. */
    val fontProgram: ByteArray by lazy { buildGlyphlessTrueType() }

    /**
     * The ToUnicode map: every 2-byte code maps to the same UTF-16 code unit, except codes in the surrogate
     * range, which [TextEncoder] assigns to characters outside the Basic Multilingual Plane.
     */
    fun toUnicodeCMap(supplementary: Map<Int, Int>): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        // A bfrange can only vary the last byte, so map each high byte as its own range
        (0 until 256).filter { it !in SURROGATE_HIGH_BYTES }.chunked(MAX_CMAP_ENTRIES).forEach { highBytes ->
            append("${highBytes.size} beginbfrange\n")
            highBytes.forEach { high ->
                val prefix = hex(high, 2)
                append("<${prefix}00> <${prefix}FF> <${prefix}00>\n")
            }
            append("endbfrange\n")
        }
        supplementary.entries.chunked(MAX_CMAP_ENTRIES).forEach { entries ->
            append("${entries.size} beginbfchar\n")
            entries.forEach { (codePoint, code) ->
                val high = 0xD800 + ((codePoint - 0x10000) shr 10)
                val low = 0xDC00 + ((codePoint - 0x10000) and 0x3FF)
                append("<${hex(code, 4)}> <${hex(high, 4)}${hex(low, 4)}>\n")
            }
            append("endbfchar\n")
        }
        append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
    }

    /**
     * Turns text into 2-byte codes for this font. Characters in the Basic Multilingual Plane are their own
     * code. Others, like emoji, can't be one code unit, so each gets an unused code from the surrogate range
     * for the whole document, which the ToUnicode map maps back.
     */
    class TextEncoder {
        /** Code point to code, for characters outside the Basic Multilingual Plane. */
        val supplementary = linkedMapOf<Int, Int>()

        fun encode(text: String): List<Int> {
            val codes = mutableListOf<Int>()
            var i = 0
            while (i < text.length) {
                val char = text[i]
                val next = text.getOrNull(i + 1)
                if (char.isHighSurrogate() && next != null && next.isLowSurrogate()) {
                    val codePoint = 0x10000 + ((char.code - 0xD800) shl 10) + (next.code - 0xDC00)
                    codes += supplementary[codePoint] ?: if (supplementary.size < SURROGATE_CODES) {
                        (0xD800 + supplementary.size).also { supplementary[codePoint] = it }
                    } else {
                        REPLACEMENT_CHARACTER
                    }
                    i += 2
                } else {
                    codes += if (char.isSurrogate()) REPLACEMENT_CHARACTER else char.code
                    i++
                }
            }
            return codes
        }
    }

    private val SURROGATE_HIGH_BYTES = 0xD8..0xDF
    private const val SURROGATE_CODES = 0x800
    private const val REPLACEMENT_CHARACTER = 0xFFFD

    /** The most entries a CMap block may have. */
    private const val MAX_CMAP_ENTRIES = 100

    private fun hex(value: Int, digits: Int) = value.toString(16).uppercase().padStart(digits, '0')

    private const val UNITS_PER_EM = 1000
    private const val GLYPH_COUNT = 2

    /**
     * Builds a TrueType font with two empty glyphs (.notdef and one blank glyph) and just the tables a PDF
     * TrueType font program needs.
     */
    private fun buildGlyphlessTrueType(): ByteArray {
        val head = BigEndianWriter().apply {
            u32(0x00010000) // version
            u32(0x00010000) // fontRevision
            u32(0) // checkSumAdjustment, filled in below
            u32(0x5F0F3CF5) // magicNumber
            u16(0x0003) // flags: baseline at y=0, left sidebearing at x=0
            u16(UNITS_PER_EM)
            u32(0); u32(0) // created
            u32(0); u32(0) // modified
            s16(0); s16(DESCENT); s16(GLYPH_WIDTH); s16(ASCENT) // xMin, yMin, xMax, yMax
            u16(0) // macStyle
            u16(3) // lowestRecPPEM
            s16(2) // fontDirectionHint
            s16(0) // indexToLocFormat: short offsets
            s16(0) // glyphDataFormat
        }.bytes()
        val hhea = BigEndianWriter().apply {
            u32(0x00010000)
            s16(ASCENT); s16(DESCENT); s16(0) // ascender, descender, lineGap
            u16(GLYPH_WIDTH) // advanceWidthMax
            s16(0); s16(0); s16(0) // minLeftSideBearing, minRightSideBearing, xMaxExtent
            s16(1); s16(0); s16(0) // caretSlopeRise, caretSlopeRun, caretOffset
            s16(0); s16(0); s16(0); s16(0) // reserved
            s16(0) // metricDataFormat
            u16(GLYPH_COUNT) // numberOfHMetrics
        }.bytes()
        val maxp = BigEndianWriter().apply {
            u32(0x00010000)
            u16(GLYPH_COUNT)
            u16(0); u16(0); u16(0); u16(0) // maxPoints, maxContours, maxCompositePoints, maxCompositeContours
            u16(2) // maxZones
            u16(0); u16(0); u16(0); u16(0); u16(0); u16(0); u16(0); u16(0) // remaining limits
        }.bytes()
        val hmtx = BigEndianWriter().apply {
            repeat(GLYPH_COUNT) {
                u16(GLYPH_WIDTH)
                s16(0)
            }
        }.bytes()
        // .notdef is a simple glyph with no contours; the second glyph is empty
        val glyf = BigEndianWriter().apply {
            s16(0) // numberOfContours
            s16(0); s16(0); s16(0); s16(0) // bounding box
            u16(0) // instructionLength
        }.bytes()
        val loca = BigEndianWriter().apply {
            // Short offsets are stored divided by two
            u16(0)
            u16(glyf.size / 2)
            u16(glyf.size / 2)
        }.bytes()
        val post = BigEndianWriter().apply {
            u32(0x00030000) // version 3: no glyph names
            u32(0) // italicAngle
            s16(-100); s16(50) // underlinePosition, underlineThickness
            u32(0) // isFixedPitch
            u32(0); u32(0); u32(0); u32(0) // memory usage hints
        }.bytes()

        val os2 = BigEndianWriter().apply {
            u16(4) // version
            s16(GLYPH_WIDTH) // xAvgCharWidth
            u16(400) // usWeightClass: regular
            u16(5) // usWidthClass: medium
            u16(0) // fsType: installable, so it can be embedded
            s16(650); s16(600); s16(0); s16(75) // subscript size and offset
            s16(650); s16(600); s16(0); s16(350) // superscript size and offset
            s16(50); s16(250) // strikeout size and position
            s16(0) // sFamilyClass
            repeat(10) { u8(0) } // panose
            repeat(4) { u32(0) } // ulUnicodeRange
            "NONE".forEach { u8(it.code) } // achVendID
            u16(0x0040) // fsSelection: regular
            u16(0); u16(0xFFFF) // usFirstCharIndex, usLastCharIndex
            s16(ASCENT); s16(DESCENT); s16(0) // sTypoAscender, sTypoDescender, sTypoLineGap
            u16(ASCENT); u16(-DESCENT) // usWinAscent, usWinDescent
            u32(0); u32(0) // ulCodePageRange
            s16(500); s16(700) // sxHeight, sCapHeight
            u16(0); u16(0x20); u16(1) // usDefaultChar, usBreakChar, usMaxContext
        }.bytes()
        // Glyphs are chosen by CID, so the cmap only needs its required end segment
        val cmap = BigEndianWriter().apply {
            u16(0) // version
            u16(1) // numTables
            u16(3); u16(1); u32(12) // Windows Unicode subtable at offset 12
            u16(4) // format
            u16(24) // length
            u16(0) // language
            u16(2); u16(2); u16(0); u16(0) // segCountX2, searchRange, entrySelector, rangeShift
            u16(0xFFFF) // endCode
            u16(0) // reservedPad
            u16(0xFFFF) // startCode
            s16(1) // idDelta
            u16(0) // idRangeOffset
        }.bytes()
        val names = listOf(1 to NAME, 2 to "Regular", 4 to NAME, 6 to NAME)
        val name = BigEndianWriter().apply {
            u16(0) // format
            u16(names.size)
            u16(6 + 12 * names.size) // offset to the strings
            var stringOffset = 0
            names.forEach { (id, value) ->
                u16(3); u16(1); u16(0x0409) // Windows, Unicode, English (US)
                u16(id)
                u16(value.length * 2)
                u16(stringOffset)
                stringOffset += value.length * 2
            }
            names.forEach { (_, value) -> value.forEach { u16(it.code) } }
        }.bytes()

        // Tables must be listed in tag order
        val tables = listOf(
            "OS/2" to os2,
            "cmap" to cmap,
            "glyf" to glyf,
            "head" to head,
            "hhea" to hhea,
            "hmtx" to hmtx,
            "loca" to loca,
            "maxp" to maxp,
            "name" to name,
            "post" to post
        )

        val directorySize = 12 + 16 * tables.size
        val offsets = mutableListOf<Int>()
        var offset = directorySize
        tables.forEach { (_, data) ->
            offsets += offset
            offset += padded(data.size)
        }

        val font = BigEndianWriter().apply {
            u32(0x00010000) // sfntVersion
            u16(tables.size)
            var entrySelector = 0
            while (2 shl entrySelector <= tables.size) entrySelector++
            val searchRange = (1 shl entrySelector) * 16
            u16(searchRange)
            u16(entrySelector)
            u16(tables.size * 16 - searchRange)
            tables.forEachIndexed { index, (tag, data) ->
                tag.forEach { u8(it.code) }
                u32(checksum(data))
                u32(offsets[index].toLong())
                u32(data.size.toLong())
            }
            tables.forEach { (_, data) ->
                bytes(data)
                repeat(padded(data.size) - data.size) { u8(0) }
            }
        }.bytes()

        // The whole-font checksum adjustment goes into the head table
        val adjustment = (0xB1B0AFBAL - checksum(font)) and 0xFFFFFFFFL
        val headOffset = offsets[tables.indexOfFirst { it.first == "head" }] + 8
        for (i in 0 until 4) {
            font[headOffset + i] = (adjustment shr (24 - 8 * i)).toByte()
        }
        return font
    }

    private fun padded(size: Int): Int = (size + 3) / 4 * 4

    private fun checksum(data: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i < data.size) {
            var word = 0L
            for (j in 0 until 4) {
                word = (word shl 8) or ((if (i + j < data.size) data[i + j].toInt() and 0xFF else 0).toLong())
            }
            sum = (sum + word) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    private class BigEndianWriter {
        private val out = mutableListOf<Byte>()

        fun u8(value: Int) {
            out += value.toByte()
        }

        fun u16(value: Int) {
            u8(value shr 8)
            u8(value)
        }

        fun s16(value: Int) = u16(value and 0xFFFF)

        fun u32(value: Int) = u32(value.toLong() and 0xFFFFFFFFL)

        fun u32(value: Long) {
            u8((value shr 24).toInt())
            u8((value shr 16).toInt())
            u8((value shr 8).toInt())
            u8(value.toInt())
        }

        fun bytes(data: ByteArray) {
            data.forEach { out += it }
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
