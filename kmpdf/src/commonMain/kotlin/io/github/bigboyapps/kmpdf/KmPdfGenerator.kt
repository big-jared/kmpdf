package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Controls the logging behavior of KmPDF.
 */
object KmPdfLogging {
    private var minSeverity: Severity = Severity.Error

    /**
     * Sets the minimum log level for KmPDF.
     *
     * By default, only errors are logged. Set to [Severity.Debug] to see detailed logs
     * about PDF generation progress.
     *
     * @param severity The minimum severity level to log. Messages below this level will be suppressed.
     *
     * Example:
     * ```kotlin
     * // Enable debug logging
     * KmPdfLogging.setMinSeverity(Severity.Debug)
     *
     * // Only show errors (default)
     * KmPdfLogging.setMinSeverity(Severity.Error)
     * ```
     */
    fun setMinSeverity(severity: Severity) {
        minSeverity = severity
    }

    /**
     * Gets the current minimum log severity level.
     */
    fun getMinSeverity(): Severity = minSeverity

    internal fun isLoggable(severity: Severity): Boolean {
        return severity.ordinal >= minSeverity.ordinal
    }
}

internal inline fun Logger.logDebug(message: () -> String) {
    if (KmPdfLogging.isLoggable(Severity.Debug)) {
        d(message())
    }
}

internal inline fun Logger.logInfo(message: () -> String) {
    if (KmPdfLogging.isLoggable(Severity.Info)) {
        i(message())
    }
}

/**
 * Defines the size of a PDF page.
 *
 * Note: The width and height values are in points (1 point = 1/72 inch), though represented
 * as Dp for convenience. These are the standard PDF page dimensions, not screen pixels.
 *
 * @property width The width of the page in points.
 * @property height The height of the page in points, or [Dp.Unspecified] for a page that's as tall as
 *                  its content (see [wrapHeight]).
 */
data class PageSize(
    val width: Dp,
    val height: Dp
) {
    companion object {
        /** A4 page size (210mm × 297mm) */
        val A4 = PageSize(width = 595.dp, height = 842.dp)

        /** US Letter page size (8.5" × 11") */
        val Letter = PageSize(width = 612.dp, height = 792.dp)

        /** US Legal page size (8.5" × 14") */
        val Legal = PageSize(width = 612.dp, height = 1008.dp)

        /** A3 page size (297mm × 420mm) */
        val A3 = PageSize(width = 842.dp, height = 1191.dp)

        /** A5 page size (148mm × 210mm) */
        val A5 = PageSize(width = 420.dp, height = 595.dp)

        /** Tabloid page size (11" × 17") */
        val Tabloid = PageSize(width = 792.dp, height = 1224.dp)

        /**
         * A page of the given width that's exactly as tall as its content plus the vertical margins,
         * rounded up to whole points. Useful for receipts, tickets, and single long pages.
         *
         * Content taller than 14,400 points (the largest page a PDF can have) makes generation return
         * [PdfResult.Error.RenderingFailed].
         */
        fun wrapHeight(width: Dp): PageSize = PageSize(width = width, height = Dp.Unspecified)
    }
}

/**
 * Defines the pages of a PDF document.
 *
 * [page] renders its content exactly as provided, with no automatic pagination. [pages] flows a list
 * of items across as many pages as they need.
 */
class PdfPageScope internal constructor() {
    internal val pages = mutableListOf<PageSpec>()

    /**
     * Adds a page to the PDF document, sized by [PdfConfig.pageSize].
     *
     * @param content The composable content for this page. Content should be sized to fit
     *                within the page dimensions specified in PdfConfig.
     */
    fun page(content: @Composable () -> Unit) {
        pages.add(PageSpec.Single(size = null, content = content))
    }

    /**
     * Adds a page with its own size instead of [PdfConfig.pageSize], so one document can mix page sizes.
     *
     * @param size The page size. Use [PageSize.wrapHeight] for a page that's as tall as its content.
     * @param content The composable content for this page.
     */
    fun page(size: PageSize, content: @Composable () -> Unit) {
        pages.add(PageSpec.Single(size = size, content = content))
    }

    /**
     * Flows [items] across as many pages as they need, in order, never splitting an item across pages.
     *
     * Every item is measured at the content width (the page width minus the margins), then items are
     * placed from the top of each page until the next one doesn't fit. Pages use [PdfConfig.pageSize],
     * which must have a fixed height. Items shouldn't fill the page height, since they're laid out in a column.
     *
     * An item taller than the space available on a page makes generation return
     * [PdfResult.Error.RenderingFailed].
     *
     * ```kotlin
     * pages(
     *     items = invoiceLines,
     *     itemSpacing = 4.dp,
     *     header = { Text("Invoice #1234") },
     *     footer = { info -> Text("Page ${info.pageNumber} of ${info.pageCount}") }
     * ) { line ->
     *     InvoiceLineRow(line)
     * }
     * ```
     *
     * @param items The items to lay out, in order.
     * @param itemSpacing Space between consecutive items on the same page.
     * @param header Content at the top of every page, given the page's [PdfPageInfo].
     * @param footer Content at the bottom of every page, given the page's [PdfPageInfo].
     * @param itemContent The content for one item.
     */
    fun <T> pages(
        items: List<T>,
        itemSpacing: Dp = 0.dp,
        header: (@Composable (PdfPageInfo) -> Unit)? = null,
        footer: (@Composable (PdfPageInfo) -> Unit)? = null,
        itemContent: @Composable (T) -> Unit
    ) {
        require(itemSpacing.value >= 0f) { "itemSpacing must be zero or positive, got $itemSpacing" }
        pages.add(
            PageSpec.Flow(
                items = items.map { item -> @Composable { itemContent(item) } },
                itemSpacing = itemSpacing,
                header = header,
                footer = footer
            )
        )
    }
}

/** Pages requested with [PdfPageScope], before their sizes and page breaks are resolved. */
internal sealed class PageSpec {
    /** One page, sized by [size] or the configured page size. */
    class Single(val size: PageSize?, val content: @Composable () -> Unit) : PageSpec()

    /** Items that flow across as many pages as they need. */
    class Flow(
        val items: List<@Composable () -> Unit>,
        val itemSpacing: Dp,
        val header: (@Composable (PdfPageInfo) -> Unit)?,
        val footer: (@Composable (PdfPageInfo) -> Unit)?
    ) : PageSpec()
}

/**
 * Space between the edges of each page and its content, in points (1 point = 1/72 inch).
 *
 * Page content is laid out inside the margins and clipped to them. Like [PageSize], values are
 * points represented as Dp.
 *
 * @throws IllegalArgumentException if any margin is negative or unspecified.
 */
data class PdfMargins(
    val left: Dp = 0.dp,
    val top: Dp = 0.dp,
    val right: Dp = 0.dp,
    val bottom: Dp = 0.dp
) {
    init {
        require(left.value >= 0f && top.value >= 0f && right.value >= 0f && bottom.value >= 0f) {
            "Margins must be zero or positive, got $this"
        }
    }

    companion object {
        /** No margins: content fills the whole page. */
        val None = PdfMargins()

        /** 0.5 inch on every side. */
        val Narrow = all(36.dp)

        /** 1 inch on every side. */
        val Normal = all(72.dp)

        /** 1 inch at the top and bottom, 2 inches on the left and right. */
        val Wide = PdfMargins(left = 144.dp, top = 72.dp, right = 144.dp, bottom = 72.dp)

        /** The same margin on every side. */
        fun all(margin: Dp) = PdfMargins(margin, margin, margin, margin)

        /** The same margin on the left and right, and on the top and bottom. */
        fun symmetric(horizontal: Dp = 0.dp, vertical: Dp = 0.dp) =
            PdfMargins(left = horizontal, top = vertical, right = horizontal, bottom = vertical)
    }
}

/**
 * Configuration for PDF generation.
 *
 * @property pageSize The size of each page in the PDF, unless a page sets its own. Defaults to A4.
 *                  Use [PageSize.wrapHeight] for pages that are as tall as their content.
 * @property fileName The name of the generated PDF file. Defaults to "document.pdf".
 * @property outputDirectory The directory path where the PDF will be saved.
 *                          Defaults to platform-specific location. On Desktop/JVM, defaults to "~/Documents/pdfs/".
 *                          Ignored on Android and iOS which use platform-specific directories.
 * @property margins Space between the page edges and the content. Defaults to [PdfMargins.None].
 *                   Margins that leave no room for content make generation return an error.
 * @property contentTimeout How long to wait for page content that reports [PdfContentLoading] before
 *                          generation fails with [PdfResult.Error.RenderingFailed]. Defaults to 10 seconds.
 */
data class PdfConfig(
    val pageSize: PageSize = PageSize.A4,
    val fileName: String = "document.pdf",
    val outputDirectory: String? = null,
    val margins: PdfMargins = PdfMargins.None,
    val contentTimeout: Duration = 10.seconds
) {
    init {
        require(contentTimeout.isPositive()) { "contentTimeout must be positive, got $contentTimeout" }
    }

    /** Keeps Java callers and apps compiled against KmPDF 1.2.0 and earlier working. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor() : this(PageSize.A4, "document.pdf", null, PdfMargins.None, 10.seconds)

    /** Keeps apps compiled against KmPDF 1.2.0 and earlier working. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(
        pageSize: PageSize = PageSize.A4,
        fileName: String = "document.pdf",
        outputDirectory: String? = null
    ) : this(pageSize, fileName, outputDirectory, PdfMargins.None, 10.seconds)

    /** Keeps apps compiled against KmPDF 1.2.0 and earlier working. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun copy(
        pageSize: PageSize = this.pageSize,
        fileName: String = this.fileName,
        outputDirectory: String? = this.outputDirectory
    ): PdfConfig = PdfConfig(pageSize, fileName, outputDirectory, margins, contentTimeout)
}

/**
 * Generator for creating PDF documents from Compose UI content.
 *
 * Use [createKmPdfGenerator] to obtain an instance of this interface.
 *
 * Example:
 * ```kotlin
 * val generator = createKmPdfGenerator()
 * val result = generator.generatePdf(
 *     config = PdfConfig(
 *         pageSize = PageSize.A4,
 *         fileName = "my-document.pdf"
 *     )
 * ) {
 *     page {
 *         Text("Page 1 content")
 *     }
 *     page {
 *         Text("Page 2 content")
 *     }
 * }
 * ```
 */
interface KmPdfGenerator {
    /**
     * Generates a PDF document from the provided pages.
     *
     * Each page is rendered independently at the exact page size specified in the config.
     * No automatic pagination or margins are applied - the user is responsible for
     * ensuring content fits within page boundaries.
     *
     * @param config Configuration for the PDF generation, including page size and output file name.
     * @param pages Builder function for defining pages using [PdfPageScope.page].
     * @return [PdfResult.Success] with the PDF file information if generation succeeds,
     *         or [PdfResult.Error] if an error occurs.
     */
    suspend fun generatePdf(
        config: PdfConfig = PdfConfig(),
        pages: PdfPageScope.() -> Unit
    ): PdfResult
}

/**
 * Result of a PDF generation operation.
 */
sealed class PdfResult {
    /**
     * PDF generation succeeded.
     *
     * @property uri The URI of the generated PDF file. On Android, this will be a content:// URI
     *               if FileProvider is configured, otherwise a file:// URI. On iOS, this will be
     *               an absolute file path. On Web, this will be a blob: URL.
     * @property filePath The absolute file path to the generated PDF. On Web, this is the file name.
     * @property fileSize The size of the generated PDF file in bytes.
     * @property pageCount The number of pages in the generated PDF.
     */
    data class Success(
        val uri: String,
        val filePath: String,
        val fileSize: Long,
        val pageCount: Int
    ) : PdfResult()

    /**
     * PDF generation failed.
     */
    sealed class Error(
        open val message: String,
        open val exception: Throwable? = null
    ) : PdfResult() {
        /**
         * KmPdfGenerator was not initialized. On Android this happens automatically at startup; if the
         * KmPdfInitializer provider was removed, call [initKmPdfGenerator] first.
         */
        data class NotInitialized(
            override val message: String = "KmPdfGenerator not initialized. Call initKmPdfGenerator(context) first."
        ) : Error(message)

        /**
         * Activity reference was lost. This can happen if the Activity is destroyed during PDF generation.
         */
        data class ActivityLost(
            override val message: String = "Activity reference lost. Please reinitialize KmPdfGenerator."
        ) : Error(message)

        /**
         * Failed to render the composable content to a bitmap.
         */
        data class RenderingFailed(
            override val message: String,
            override val exception: Throwable? = null
        ) : Error(message, exception)

        /**
         * Failed to write the PDF to disk or perform other I/O operations.
         */
        data class IOError(
            override val message: String,
            override val exception: Throwable? = null
        ) : Error(message, exception)

        /**
         * An unexpected error occurred during PDF generation.
         */
        data class Unknown(
            override val message: String,
            override val exception: Throwable? = null
        ) : Error(message, exception)
    }
}

/**
 * Creates a platform-specific instance of [KmPdfGenerator].
 *
 * **Android**: Initialized automatically at startup. If you removed the KmPdfInitializer provider,
 * call [initKmPdfGenerator] first, typically in your Activity's onCreate.
 * **iOS**: No initialization required.
 *
 * @return A platform-specific implementation of [KmPdfGenerator].
 */
expect fun createKmPdfGenerator(): KmPdfGenerator

/**
 * Opens the platform's native share sheet to share a PDF file.
 *
 * **Android**: Opens an Android share intent with the PDF file.
 * **iOS**: Presents a UIActivityViewController with the PDF file.
 * **Desktop**: Opens the PDF in the default viewer.
 * **Web**: Downloads the PDF, then frees it from memory shortly after.
 *
 * @param uri The URI or file path of the PDF to share. This should be the URI returned
 *            from [PdfResult.Success].
 * @param title The title to display in the share sheet. Defaults to "Share PDF".
 */
expect fun sharePdf(uri: String, title: String = "Share PDF")

/**
 * Reads the bytes of a generated PDF, for example to upload it or store it somewhere else.
 *
 * **Android**: Reads the content:// or file: URI, or file path, returned in [PdfResult.Success.uri].
 * **iOS** and **Desktop**: Reads the file at the returned path.
 * **Web**: Reads the blob: URL. Call it before [sharePdf] frees the PDF.
 *
 * @param uri The URI or file path returned in [PdfResult.Success.uri].
 * @throws Exception when the PDF can't be read, for example because it no longer exists.
 */
expect suspend fun readPdfBytes(uri: String): ByteArray
