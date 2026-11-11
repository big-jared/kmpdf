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
