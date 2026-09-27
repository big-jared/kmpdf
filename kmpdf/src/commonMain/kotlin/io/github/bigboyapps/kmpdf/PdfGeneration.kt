package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** A rendered page: RGB pixels flattened onto white, and the text lines on it. */
internal class RenderedPage(
    val rgb: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val textLines: List<PageTextLine>
)

/** What each platform provides to generate a PDF. Planning, writing, and error handling are shared. */
internal interface PdfPlatform {
    /** Measures how tall each of [contents] is at [widthPx] with no height limit, once they're ready. */
    suspend fun measureContentHeightsPx(
        contents: List<@Composable () -> Unit>,
        widthPx: Int,
        contentTimeout: Duration
    ): List<Int>

    /** Renders [content] once it's ready and collects the text on it. */
    suspend fun renderPage(content: @Composable () -> Unit, widthPx: Int, heightPx: Int, contentTimeout: Duration): RenderedPage

    /** zlib-compresses [data], or returns null when compression isn't available. */
    suspend fun compress(data: ByteArray): ByteArray?

    /** Stores the finished PDF, replacing any earlier one of the same name, and describes where it is. */
    suspend fun save(pdf: ByteArray, config: PdfConfig, pageCount: Int): PdfResult.Success
}

/**
 * Generates a PDF with [platform]: plans pages, renders each one, and writes a PDF where each page is an
 * image with an invisible text layer over it.
 *
 * Nothing is saved unless every page renders, so a failed or cancelled generation never leaves a partial PDF.
 */
internal suspend fun generatePdfWith(
    platform: PdfPlatform,
    config: PdfConfig,
    pages: PdfPageScope.() -> Unit,
    logger: Logger
): PdfResult {
    logger.logDebug { "Starting PDF generation: ${config.fileName}" }

    val pageScope = PdfPageScope()
    pageScope.pages()
    if (pageScope.pages.isEmpty()) {
        return PdfResult.Error.Unknown("No pages provided")
    }

    val plannedPages = try {
        planPages(config, pageScope.pages) { contents, widthPx ->
            platform.measureContentHeightsPx(contents, widthPx, config.contentTimeout)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: PdfConfigException) {
        return PdfResult.Error.Unknown(e.message ?: "Invalid page configuration")
    } catch (e: Throwable) {
        logger.e(e) { "Failed to measure pages: ${e.message}" }
        return PdfResult.Error.RenderingFailed("Failed to measure pages: ${e.message}", e)
    }

    logger.logDebug { "Rendering ${plannedPages.size} pages" }

    val writer = RasterPdfWriter()
    writer.info = config.metadata.infoEntries()

    // Render and compress one page at a time, so only one page's pixels are in memory
    plannedPages.forEachIndexed { index, plannedPage ->
        currentCoroutineContext().ensureActive()
        logger.logDebug { "Rendering page ${index + 1} of ${plannedPages.size}" }

        val rendered = try {
            platform.renderPage(
                plannedPage.content,
                (plannedPage.widthPt * PAGE_RENDER_SCALE).toInt(),
                (plannedPage.heightPt * PAGE_RENDER_SCALE).toInt(),
                config.contentTimeout
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(e) { "Failed to render page ${index + 1}: ${e.message}" }
            return PdfResult.Error.RenderingFailed("Failed to render page ${index + 1}: ${e.message}", e)
        }

        val compressed = try {
            platform.compress(rendered.rgb)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e(e) { "Failed to compress page ${index + 1}: ${e.message}" }
            return PdfResult.Error.IOError("Failed to compress page ${index + 1}: ${e.message}", e)
        }

        writer.addPage(
            RasterPdfWriter.PageImage(
                widthPt = plannedPage.widthPt,
                heightPt = plannedPage.heightPt,
                widthPx = rendered.widthPx,
                heightPx = rendered.heightPx,
                data = compressed ?: rendered.rgb,
                flateCompressed = compressed != null,
                textLines = rendered.textLines
            )
        )
    }
    currentCoroutineContext().ensureActive()

    return try {
        val pdf = writer.build()
        platform.save(pdf, config, plannedPages.size).also { result ->
            logger.logInfo { "PDF generation successful: ${result.filePath} (${result.pageCount} pages, ${result.fileSize} bytes)" }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.e(e) { "Failed to write the PDF: ${e.message}" }
        PdfResult.Error.IOError("Failed to write the PDF: ${e.message}", e)
    }
}
