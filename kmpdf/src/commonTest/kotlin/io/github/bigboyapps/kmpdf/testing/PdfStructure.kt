package io.github.bigboyapps.kmpdf.testing

/**
 * A minimal, strict PDF reader for tests.
 *
 * It validates the file's structure without repairing anything: the header and EOF marker, every
 * cross-reference table in the update chain, and that each in-use cross-reference entry points exactly
 * at its object. It then exposes the page tree, page sizes, image XObjects, and the Info dictionary.
 * Only classic cross-reference tables are supported; a file using cross-reference streams is rejected.
 */
class PdfStructure private constructor(private val bytes: ByteArray) {
    private val text: String = bytes.latin1()
    private val offsets = mutableMapOf<Int, Int>()
    private val trailer = mutableMapOf<String, PdfValue>()

    init {
        check(text.startsWith("%PDF-")) { "File doesn't start with %PDF-" }
        check(text.trimEnd().endsWith("%%EOF")) { "File doesn't end with %%EOF" }

        var xrefOffset: Int? = startXref()
        val visited = mutableSetOf<Int>()
        while (xrefOffset != null) {
            check(visited.add(xrefOffset)) { "Cross-reference chain loops at offset $xrefOffset" }
            xrefOffset = readXrefSection(xrefOffset)
        }
        check(offsets.isNotEmpty()) { "Cross-reference table has no in-use objects" }

        offsets.forEach { (number, offset) ->
            val header = Regex("""(\d+)\s+(\d+)\s+obj""").matchAt(text, offset)
            check(header != null && header.groupValues[1].toInt() == number) {
                "Cross-reference entry for object $number points at offset $offset, which isn't that object"
            }
        }
    }

    val pdfVersion: String get() = text.substring(5, text.indexOf('\n').coerceAtMost(12)).trim()

    /** Page dictionaries in document order, with inheritable attributes resolved. */
    val pages: List<PdfPage> by lazy {
        val root = resolve(trailer["Root"]) as? PdfValue.Dict ?: error("Trailer has no /Root dictionary")
        val pageTree = resolve(root["Pages"]) as? PdfValue.Dict ?: error("Catalog has no /Pages dictionary")
        val result = mutableListOf<PdfPage>()
        collectPages(pageTree, inheritedMediaBox = null, out = result, depth = 0)
        val declaredCount = (resolve(pageTree["Count"]) as? PdfValue.Number)?.value?.toInt()
        check(declaredCount == result.size) { "Page tree /Count is $declaredCount but it has ${result.size} pages" }
        result
    }

    /** Info dictionary entries, decoded to text. Empty when the file has no Info dictionary. */
    val info: Map<String, String> by lazy {
        val dict = resolve(trailer["Info"]) as? PdfValue.Dict ?: return@lazy emptyMap()
        dict.entries.mapNotNull { (key, value) ->
            (resolve(value) as? PdfValue.Str)?.let { key to it.decode() }
        }.toMap()
    }

    /** Image XObjects drawn on the page at [pageIndex]. */
    fun images(pageIndex: Int): List<PdfImage> {
        val resources = resolve(pages[pageIndex].dict["Resources"]) as? PdfValue.Dict ?: return emptyList()
        val xObjects = resolve(resources["XObject"]) as? PdfValue.Dict ?: return emptyList()
        return xObjects.entries.values.mapNotNull { ref ->
            val stream = resolve(ref) as? PdfValue.Stream ?: return@mapNotNull null
            if ((stream.dict["Subtype"] as? PdfValue.Name)?.value != "Image") return@mapNotNull null
            PdfImage(
                width = (resolve(stream.dict["Width"]) as PdfValue.Number).value.toInt(),
                height = (resolve(stream.dict["Height"]) as PdfValue.Number).value.toInt(),
                colorSpace = (resolve(stream.dict["ColorSpace"]) as? PdfValue.Name)?.value,
                bitsPerComponent = (resolve(stream.dict["BitsPerComponent"]) as? PdfValue.Number)?.value?.toInt(),
                filters = when (val filter = resolve(stream.dict["Filter"])) {
                    is PdfValue.Name -> listOf(filter.value)
                    is PdfValue.Array -> filter.items.map { (resolve(it) as PdfValue.Name).value }
                    else -> emptyList()
                },
                data = stream.data
            )
        }
    }

    /**
     * The text shown on the page at [pageIndex], one run per text-showing operator, in content order.
     *
     * Only what KmPDF writes is supported: uncompressed content streams, and hex strings in an embedded
     * composite font with Identity-H encoding. Codes are decoded through the font's ToUnicode map, the way
     * viewers extract text.
     */
    fun textRuns(pageIndex: Int): List<PdfTextRun> {
        val page = pages[pageIndex].dict
        val contents = resolve(page["Contents"]) as? PdfValue.Stream ?: return emptyList()
        check(contents.dict["Filter"] == null) { "Only uncompressed content streams are supported" }

        val resources = resolve(page["Resources"]) as? PdfValue.Dict
        val fonts = resolve(resources?.get("Font")) as? PdfValue.Dict

        val runs = mutableListOf<PdfTextRun>()
        val operands = mutableListOf<String>()
        var inText = false
        var fontSize = 0f
        var horizontalScale = 100f
        var renderMode = 0
        var x = 0f
        var y = 0f
        var toUnicode: ToUnicodeMap? = null
        for (token in contentTokens(contents.data.latin1())) {
            if (token.first() == '/' || token.first() == '<' || token.first() == '[' || token.toFloatOrNull() != null) {
                operands += token
                continue
            }
            when (token) {
                "BT" -> {
                    check(!inText) { "Nested BT on page ${pageIndex + 1}" }
                    inText = true
                    x = 0f
                    y = 0f
                }
                "ET" -> {
                    check(inText) { "ET without BT on page ${pageIndex + 1}" }
                    inText = false
                }
                "Tr" -> renderMode = operands.last().toFloat().toInt()
                "Tz" -> horizontalScale = operands.last().toFloat()
                "Tf" -> {
                    val fontName = operands[operands.size - 2].removePrefix("/")
                    toUnicode = identityFontToUnicode(resolve(fonts?.get(fontName)) as? PdfValue.Dict, fontName)
                    fontSize = operands.last().toFloat()
                }
                "Tm" -> {
                    val matrix = operands.takeLast(6).map { it.toFloat() }
                    check(matrix[0] == 1f && matrix[1] == 0f && matrix[2] == 0f && matrix[3] == 1f) {
                        "Only translation text matrices are supported"
                    }
                    x = matrix[4]
                    y = matrix[5]
                }
                "Tj" -> {
                    check(inText) { "Text shown outside BT/ET on page ${pageIndex + 1}" }
                    val hex = operands.last()
                    check(hex.startsWith("<") && hex.length % 4 == 2) { "Expected a hex string of 2-byte codes, got $hex" }
                    val map = checkNotNull(toUnicode) { "Text shown before a font was set on page ${pageIndex + 1}" }
                    val text = hex.removeSurrounding("<", ">").chunked(4).joinToString("") { map.decode(it.toInt(16)) }
                    runs += PdfTextRun(text, x, y, fontSize, horizontalScale, renderMode)
                }
            }
            operands.clear()
        }
        check(!inText) { "BT without ET on page ${pageIndex + 1}" }
        return runs
    }

    private fun identityFontToUnicode(font: PdfValue.Dict?, name: String): ToUnicodeMap {
        checkNotNull(font) { "Font /$name isn't in the page's resources" }
        check((font["Subtype"] as? PdfValue.Name)?.value == "Type0") { "Font /$name isn't a composite font" }
        check((font["Encoding"] as? PdfValue.Name)?.value == "Identity-H") { "Font /$name isn't Identity-H encoded" }
        val toUnicode = resolve(font["ToUnicode"]) as? PdfValue.Stream ?: error("Font /$name has no ToUnicode map")
        val descendant = (resolve(font["DescendantFonts"]) as? PdfValue.Array)?.items?.singleOrNull()
            ?.let { resolve(it) as? PdfValue.Dict } ?: error("Font /$name has no descendant font")
        check((descendant["Subtype"] as? PdfValue.Name)?.value == "CIDFontType2") { "Font /$name isn't a TrueType CID font" }
        val descriptor = resolve(descendant["FontDescriptor"]) as? PdfValue.Dict ?: error("Font /$name has no descriptor")
        check(resolve(descriptor["FontFile2"]) is PdfValue.Stream) { "Font /$name isn't embedded" }
        return ToUnicodeMap.parse(toUnicode.data.latin1())
    }

    /** Splits a content stream into operands and operators. Arrays are kept as one token. */
    private fun contentTokens(content: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c.isPdfWhitespace() -> i++
                c == '<' || c == '[' -> {
                    val end = content.indexOf(if (c == '<') '>' else ']', i)
                    check(end >= 0) { "Unterminated ${if (c == '<') "hex string" else "array"} in content stream" }
                    tokens += content.substring(i, end + 1).filterNot { it.isPdfWhitespace() }
                    i = end + 1
                }
                c == '(' -> error("Literal strings in content streams aren't supported")
                else -> {
                    val start = i
                    i++
                    while (i < content.length && !content[i].isPdfWhitespace() && !content[i].isPdfDelimiter()) i++
                    tokens += content.substring(start, i)
                }
            }
        }
        return tokens
    }

    private fun collectPages(node: PdfValue.Dict, inheritedMediaBox: List<Float>?, out: MutableList<PdfPage>, depth: Int) {
        check(depth < MAX_PAGE_TREE_DEPTH) { "Page tree is too deep" }
        val mediaBox = (resolve(node["MediaBox"]) as? PdfValue.Array)?.items?.map {
            (resolve(it) as PdfValue.Number).value.toFloat()
        } ?: inheritedMediaBox
        when ((node["Type"] as? PdfValue.Name)?.value) {
            "Pages" -> {
                val kids = resolve(node["Kids"]) as? PdfValue.Array ?: error("/Pages node has no /Kids")
                kids.items.forEach { kid ->
                    val child = resolve(kid) as? PdfValue.Dict ?: error("Page tree kid isn't a dictionary")
                    collectPages(child, mediaBox, out, depth + 1)
                }
            }
            "Page" -> {
                check(mediaBox != null && mediaBox.size == 4) { "Page ${out.size + 1} has no valid /MediaBox" }
                out += PdfPage(node, mediaBox)
            }
            else -> error("Unexpected page tree node type ${node["Type"]}")
        }
    }

    fun resolve(value: PdfValue?): PdfValue? {
        var current = value
        var hops = 0
        while (current is PdfValue.Ref) {
            check(hops++ < MAX_REFERENCE_HOPS) { "Reference chain is too long" }
            current = readObject(current.number)
        }
        return current
    }

    private val objectCache = mutableMapOf<Int, PdfValue>()

    private fun readObject(number: Int): PdfValue {
        objectCache[number]?.let { return it }
        val offset = offsets[number] ?: error("Object $number isn't in the cross-reference table")
        val parser = Parser(text, offset)
        parser.expectKeyword(Regex("""\d+\s+\d+\s+obj"""))
        var value = parser.readValue()
        parser.skipWhitespace()
        if (parser.lookingAt("stream")) {
            val dict = value as? PdfValue.Dict ?: error("Object $number has a stream without a dictionary")
            var start = parser.position + "stream".length
            if (text.startsWith("\r\n", start)) start += 2 else if (text[start] == '\n') start += 1
            val length = (resolve(dict["Length"]) as? PdfValue.Number)?.value?.toInt()
                ?: error("Stream object $number has no /Length")
            check(start + length <= bytes.size) { "Stream object $number runs past the end of the file" }
            val afterData = Parser(text, start + length)
            afterData.skipWhitespace()
            check(afterData.lookingAt("endstream")) { "Stream object $number /Length doesn't end at endstream" }
            value = PdfValue.Stream(dict, bytes.copyOfRange(start, start + length))
        }
        objectCache[number] = value
        return value
    }

    private fun startXref(): Int {
        val index = text.lastIndexOf("startxref")
        check(index >= 0) { "No startxref" }
        val match = Regex("""startxref\s+(\d+)""").matchAt(text, index) ?: error("Malformed startxref")
        val offset = match.groupValues[1].toInt()
        check(offset in bytes.indices) { "startxref offset $offset is outside the file" }
        return offset
    }

    /** Reads one cross-reference section and its trailer. Returns the /Prev offset, if any. */
    private fun readXrefSection(offset: Int): Int? {
        check(text.startsWith("xref", offset)) {
            "No classic cross-reference table at offset $offset (cross-reference streams aren't supported)"
        }
        val parser = Parser(text, offset + "xref".length)
        while (true) {
            parser.skipWhitespace()
            if (parser.lookingAt("trailer")) break
            val first = parser.readInt()
            val count = parser.readInt()
            repeat(count) { i ->
                val entryOffset = parser.readInt()
                parser.readInt()
                val type = parser.readToken()
                check(type == "n" || type == "f") { "Bad cross-reference entry type '$type'" }
                // Newer sections are read first, so they win over older entries for the same object
                if (type == "n" && (first + i) !in offsets) offsets[first + i] = entryOffset
            }
        }
        parser.expectKeyword(Regex("trailer"))
        val dict = parser.readValue() as? PdfValue.Dict ?: error("Trailer isn't a dictionary")
        dict.entries.forEach { (key, value) -> if (key !in trailer) trailer[key] = value }
        return (dict["Prev"] as? PdfValue.Number)?.value?.toInt()
    }

    companion object {
        private const val MAX_PAGE_TREE_DEPTH = 64
        private const val MAX_REFERENCE_HOPS = 32

        fun parse(bytes: ByteArray): PdfStructure = PdfStructure(bytes)
    }
}

/** A ToUnicode CMap's bfrange and bfchar mappings from 2-byte codes to text. */
private class ToUnicodeMap(private val chars: Map<Int, String>, private val ranges: List<Triple<Int, Int, Int>>) {
    fun decode(code: Int): String {
        chars[code]?.let { return it }
        val (start, _, destination) = ranges.firstOrNull { code in it.first..it.second }
            ?: error("Code ${code.toString(16)} isn't in the ToUnicode map")
        return (destination + code - start).toChar().toString()
    }

    companion object {
        fun parse(cmap: String): ToUnicodeMap {
            val hex = """<([0-9A-Fa-f]+)>"""
            val chars = mutableMapOf<Int, String>()
            val ranges = mutableListOf<Triple<Int, Int, Int>>()
            Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL).findAll(cmap).forEach { block ->
                Regex("""$hex\s*$hex""").findAll(block.groupValues[1]).forEach { entry ->
                    val units = entry.groupValues[2].chunked(4).map { it.toInt(16).toChar() }
                    chars[entry.groupValues[1].toInt(16)] = units.joinToString("")
                }
            }
            Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL).findAll(cmap).forEach { block ->
                Regex("""$hex\s*$hex\s*$hex""").findAll(block.groupValues[1]).forEach { entry ->
                    val (start, end, destination) = entry.destructured
                    check(start.length == 4 && end.length == 4 && destination.length == 4) { "Only 2-byte ranges are supported" }
                    check(start.substring(0, 2) == end.substring(0, 2)) { "A bfrange may only vary its last byte: $start..$end" }
                    ranges += Triple(start.toInt(16), end.toInt(16), destination.toInt(16))
                }
            }
            check(chars.isNotEmpty() || ranges.isNotEmpty()) { "The ToUnicode map has no mappings" }
            return ToUnicodeMap(chars, ranges)
        }
    }
}

class PdfPage(val dict: PdfValue.Dict, val mediaBox: List<Float>) {
    val widthPt: Float get() = mediaBox[2] - mediaBox[0]
    val heightPt: Float get() = mediaBox[3] - mediaBox[1]
}

/**
 * Text shown by one operator, starting at ([x], [y]) in PDF space (from the bottom-left, in points).
 *
 * @property renderMode The text rendering mode; 3 is invisible.
 */
class PdfTextRun(
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float,
    val horizontalScale: Float,
    val renderMode: Int
) {
    /** The run's advance width in points, for a font whose glyphs are all half an em wide. */
    val width: Float get() = text.length * 0.5f * fontSize * horizontalScale / 100f
}

class PdfImage(
    val width: Int,
    val height: Int,
    val colorSpace: String?,
    val bitsPerComponent: Int?,
    val filters: List<String>,
    val data: ByteArray
)

sealed class PdfValue {
    data class Number(val value: Double) : PdfValue()
    data class Name(val value: String) : PdfValue()
    data class Bool(val value: Boolean) : PdfValue()
    data object Null : PdfValue()
    data class Ref(val number: Int, val generation: Int) : PdfValue()
    class Str(val bytes: ByteArray) : PdfValue() {
        /** Decodes UTF-16BE (with a byte order mark) or PDFDocEncoding, treated as Latin-1. */
        fun decode(): String =
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                buildString {
                    var i = 2
                    while (i + 1 < bytes.size) {
                        append((((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)).toChar())
                        i += 2
                    }
                }
            } else {
                bytes.latin1()
            }
    }
    class Array(val items: List<PdfValue>) : PdfValue()
    class Dict(val entries: Map<String, PdfValue>) : PdfValue() {
        operator fun get(key: String): PdfValue? = entries[key]
    }
    class Stream(val dict: Dict, val data: ByteArray) : PdfValue()
}

private class Parser(private val text: String, var position: Int) {
    fun skipWhitespace() {
        while (position < text.length) {
            val c = text[position]
            when {
                c == '%' -> while (position < text.length && text[position] != '\n' && text[position] != '\r') position++
                c.isPdfWhitespace() -> position++
                else -> return
            }
        }
    }

    fun lookingAt(keyword: String): Boolean = text.startsWith(keyword, position)

    fun expectKeyword(pattern: Regex) {
        skipWhitespace()
        val match = pattern.matchAt(text, position) ?: error("Expected ${pattern.pattern} at offset $position")
        position = match.range.last + 1
    }

    fun readToken(): String {
        skipWhitespace()
        val start = position
        while (position < text.length && !text[position].isPdfWhitespace() && !text[position].isPdfDelimiter()) {
            position++
        }
        check(position > start) { "Expected a token at offset $start" }
        return text.substring(start, position)
    }

    fun readInt(): Int = readToken().toIntOrNull() ?: error("Expected an integer near offset $position")

    fun readValue(): PdfValue {
        skipWhitespace()
        check(position < text.length) { "Unexpected end of file" }
        return when {
            lookingAt("<<") -> readDict()
            text[position] == '[' -> readArray()
            text[position] == '/' -> readName()
            text[position] == '(' -> readLiteralString()
            text[position] == '<' -> readHexString()
            else -> readNumberRefOrKeyword()
        }
    }

    private fun readDict(): PdfValue.Dict {
        position += 2
        val entries = linkedMapOf<String, PdfValue>()
        while (true) {
            skipWhitespace()
            if (lookingAt(">>")) {
                position += 2
                return PdfValue.Dict(entries)
            }
            val key = readName().value
            entries[key] = readValue()
        }
    }

    private fun readArray(): PdfValue.Array {
        position++
        val items = mutableListOf<PdfValue>()
        while (true) {
            skipWhitespace()
            if (text[position] == ']') {
                position++
                return PdfValue.Array(items)
            }
            items += readValue()
        }
    }

    private fun readName(): PdfValue.Name {
        skipWhitespace()
        check(text[position] == '/') { "Expected a name at offset $position" }
        position++
        val start = position
        while (position < text.length && !text[position].isPdfWhitespace() && !text[position].isPdfDelimiter()) {
            position++
        }
        val raw = text.substring(start, position)
        val decoded = Regex("#([0-9A-Fa-f]{2})").replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        return PdfValue.Name(decoded)
    }

    private fun readLiteralString(): PdfValue.Str {
        position++
        val out = mutableListOf<Byte>()
        var depth = 1
        while (true) {
            val c = text[position++]
            when {
                c == '\\' -> {
                    val next = text[position++]
                    when (next) {
                        'n' -> out += '\n'.code.toByte()
                        'r' -> out += '\r'.code.toByte()
                        't' -> out += '\t'.code.toByte()
                        'b' -> out += '\b'.code.toByte()
                        'f' -> out += 0x0C
                        '\r' -> if (position < text.length && text[position] == '\n') position++
                        '\n' -> Unit
                        in '0'..'7' -> {
                            var value = next - '0'
                            repeat(2) {
                                if (position < text.length && text[position] in '0'..'7') {
                                    value = value * 8 + (text[position++] - '0')
                                }
                            }
                            out += value.toByte()
                        }
                        else -> out += next.code.toByte()
                    }
                }
                c == '(' -> {
                    depth++
                    out += c.code.toByte()
                }
                c == ')' -> {
                    depth--
                    if (depth == 0) return PdfValue.Str(out.toByteArray())
                    out += c.code.toByte()
                }
                else -> out += c.code.toByte()
            }
        }
    }

    private fun readHexString(): PdfValue.Str {
        position++
        val end = text.indexOf('>', position)
        check(end >= 0) { "Unterminated hex string at offset $position" }
        val digits = text.substring(position, end).filterNot { it.isPdfWhitespace() }
        position = end + 1
        val padded = if (digits.length % 2 == 1) digits + "0" else digits
        return PdfValue.Str(ByteArray(padded.length / 2) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }

    private fun readNumberRefOrKeyword(): PdfValue {
        val token = readToken()
        when (token) {
            "true" -> return PdfValue.Bool(true)
            "false" -> return PdfValue.Bool(false)
            "null" -> return PdfValue.Null
        }
        val number = token.toDoubleOrNull() ?: error("Unexpected token '$token' near offset $position")
        val integer = token.toIntOrNull()
        if (integer != null) {
            // An indirect reference is "<number> <generation> R"
            val saved = position
            val ref = Regex("""\s+(\d+)\s+R(?![A-Za-z])""").matchAt(text, position)
            if (ref != null) {
                position = ref.range.last + 1
                return PdfValue.Ref(integer, ref.groupValues[1].toInt())
            }
            position = saved
        }
        return PdfValue.Number(number)
    }
}

private fun Char.isPdfWhitespace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t' ||
    this == '' || this == ' '

private fun Char.isPdfDelimiter(): Boolean = this in "()<>[]{}/%"

private fun ByteArray.latin1(): String = buildString(size) {
    this@latin1.forEach { append((it.toInt() and 0xFF).toChar()) }
}
