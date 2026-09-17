package io.github.bigboyapps.kmpdf

/** The producer recorded in generated PDFs. */
internal val KMPDF_PRODUCER: String get() = "KmPDF $KMPDF_VERSION"

/** The Info dictionary entries for this metadata, including KmPDF as the producer. */
internal fun PdfMetadata.infoEntries(): List<Pair<String, String>> = listOfNotNull(
    title?.let { "Title" to it },
    author?.let { "Author" to it },
    subject?.let { "Subject" to it },
    keywords?.let { "Keywords" to it },
    creator?.let { "Creator" to it },
    "Producer" to KMPDF_PRODUCER
)

/**
 * Encodes [value] as a PDF text string: a literal string when it's printable ASCII, otherwise UTF-16BE
 * with a byte order mark, which every PDF reader decodes.
 */
internal fun pdfTextString(value: String): String =
    if (value.all { it.code in 0x20..0x7E }) {
        "(" + value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
    } else {
        buildString {
            append("<FEFF")
            value.forEach { append(it.code.toString(16).uppercase().padStart(4, '0')) }
            append(">")
        }
    }

/**
 * Adds an Info dictionary to an existing PDF by appending an incremental update, the standard way to change
 * a PDF without rewriting it.
 *
 * Only PDFs with classic cross-reference tables are supported, which is what Android's PdfDocument writes.
 *
 * @throws IllegalArgumentException when [pdf] isn't a PDF this can update.
 */
internal fun appendInfoDictionary(pdf: ByteArray, entries: List<Pair<String, String>>): ByteArray {
    if (entries.isEmpty()) return pdf
    val text = pdf.decodeLatin1()

    val startXrefIndex = text.lastIndexOf("startxref")
    require(startXrefIndex >= 0) { "Not a PDF: no startxref" }
    val previousXref = Regex("""startxref\s+(\d+)""").find(text, startXrefIndex)?.groupValues?.get(1)?.toInt()
        ?: throw IllegalArgumentException("Not a PDF: malformed startxref")
    require(text.startsWith("xref", previousXref)) {
        "Only PDFs with classic cross-reference tables can be updated"
    }
    val trailerIndex = text.indexOf("trailer", previousXref)
    require(trailerIndex in previousXref until startXrefIndex) { "Not a PDF: no trailer" }
    val trailer = text.substring(trailerIndex, startXrefIndex)
    val size = Regex("""/Size\s+(\d+)""").find(trailer)?.groupValues?.get(1)?.toInt()
        ?: throw IllegalArgumentException("Not a PDF: trailer has no /Size")
    val root = Regex("""/Root\s+(\d+\s+\d+\s+R)""").find(trailer)?.groupValues?.get(1)
        ?: throw IllegalArgumentException("Not a PDF: trailer has no /Root")
    val id = Regex("""/ID\s*\[[^\]]*]""").find(trailer)?.value?.let { " $it" } ?: ""

    val infoNumber = size
    val separator = if (pdf.isNotEmpty() && pdf.last() == '\n'.code.toByte()) "" else "\n"
    val objectOffset = pdf.size + separator.length
    val infoObject = "$infoNumber 0 obj\n<< ${entries.joinToString(" ") { (key, value) -> "/$key ${pdfTextString(value)}" }} >>\nendobj\n"
    // The appended text is plain ASCII, so its length in characters is its length in bytes
    val xrefOffset = objectOffset + infoObject.length
    val update = separator + infoObject +
        "xref\n$infoNumber 1\n${objectOffset.toString().padStart(10, '0')} 00000 n \n" +
        "trailer\n<< /Size ${infoNumber + 1} /Root $root /Info $infoNumber 0 R$id /Prev $previousXref >>\n" +
        "startxref\n$xrefOffset\n%%EOF\n"
    return pdf + update.encodeToByteArray()
}

private fun ByteArray.decodeLatin1(): String = buildString(size) {
    this@decodeLatin1.forEach { append((it.toInt() and 0xFF).toChar()) }
}
