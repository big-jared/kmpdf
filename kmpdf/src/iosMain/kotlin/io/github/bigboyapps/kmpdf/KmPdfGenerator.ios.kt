package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGContextBeginPage
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextEndPage
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGPDFContextClose
import platform.CoreGraphics.CGPDFContextCreateWithURL
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGPDFContextAuthor
import platform.CoreGraphics.kCGPDFContextCreator
import platform.CoreGraphics.kCGPDFContextKeywords
import platform.CoreGraphics.kCGPDFContextSubject
import platform.CoreGraphics.kCGPDFContextTitle
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController
import kotlin.time.Duration

private val logger = Logger.withTag("KmPdfGenerator")

actual fun createKmPdfGenerator(): KmPdfGenerator = IosKmPdfGenerator()

@OptIn(ExperimentalForeignApi::class)
actual fun sharePdf(uri: String, title: String) {
    val presenter = topViewController() ?: run {
        logger.e { "Failed to share PDF: no view controller to present from" }
        return
    }

    val url = NSURL.fileURLWithPath(uri)
    val activityController = UIActivityViewController(
        activityItems = listOf(url),
        applicationActivities = null
    )

    // On iPad the share sheet is a popover, which UIKit refuses to present without an anchor
    activityController.popoverPresentationController?.let { popover ->
        val view = presenter.view
        popover.sourceView = view
        popover.sourceRect = view.bounds.useContents {
            CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0)
        }
        popover.permittedArrowDirections = 0uL
    }

    presenter.presentViewController(
        activityController,
        animated = true,
        completion = null
    )
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readPdfBytes(uri: String): ByteArray = withContext(Dispatchers.IO) {
    val path = if (uri.startsWith("file:")) NSURL.URLWithString(uri)?.path ?: uri else uri
    val data = NSData.dataWithContentsOfFile(path) ?: throw IllegalStateException("Couldn't read the PDF at $uri")
    ByteArray(data.length.toInt()).also { bytes ->
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
    }
}

/**
 * The view controller on top of the key window. Presenting from the root view controller fails
 * silently while it's already presenting something else.
 */
private fun topViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    val windows = application.windows.mapNotNull { it as? UIWindow }
    val window = application.keyWindow
        ?: windows.firstOrNull { it.keyWindow }
        ?: windows.firstOrNull()

    var controller = window?.rootViewController ?: return null
    while (true) {
        controller = controller.presentedViewController ?: return controller
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        pages: PdfPageScope.() -> Unit
    ): PdfResult {
        logger.logDebug { "Starting PDF generation: ${config.fileName}" }

        // Compose rendering happens on the main thread. The PDF is written through a CoreGraphics
        // context rather than UIKit's shared one, so waiting for content between pages is safe.
        return withContext(Dispatchers.Main) {
            try {
                // Build pages
                val pageScope = PdfPageScope()
                pageScope.pages()

                if (pageScope.pages.isEmpty()) {
                    return@withContext PdfResult.Error.Unknown("No pages defined")
                }

                val density = Density(PAGE_RENDER_SCALE)
                val plannedPages = try {
                    planPages(config, pageScope.pages) { contents, widthPx ->
                        measureContentHeightsPx(contents, widthPx, density, config.contentTimeout)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PdfConfigException) {
                    return@withContext PdfResult.Error.Unknown(e.message ?: "Invalid page configuration")
                } catch (e: Exception) {
                    logger.e(e) { "Failed to measure pages: ${e.message}" }
                    return@withContext PdfResult.Error.RenderingFailed("Failed to measure pages: ${e.message}", e)
                }

                logger.logDebug { "Rendering ${plannedPages.size} pages" }

                val outputPath = outputPath(config.fileName)
                // Pages go to a temporary file that replaces the output only once every page succeeds,
                // so a failed render doesn't leave a partial PDF or overwrite an earlier one
                val tempPath = "$outputPath.partial"
                val fileManager = NSFileManager.defaultManager

                val firstPage = plannedPages.first()
                val context = createPdfContext(
                    tempPath,
                    firstPage.widthPt.toDouble(),
                    firstPage.heightPt.toDouble(),
                    config.metadata
                )
                    ?: return@withContext PdfResult.Error.IOError("Failed to create PDF file at $tempPath")

                var allPagesWritten = false
                try {
                    // Render and write one page at a time so only one page image is in memory
                    plannedPages.forEachIndexed { index, plannedPage ->
                        ensureActive()
                        logger.logDebug { "Rendering page ${index + 1} of ${plannedPages.size}" }

                        val uiImage = try {
                            renderPage(
                                plannedPage.content,
                                (plannedPage.widthPt * PAGE_RENDER_SCALE).toInt(),
                                (plannedPage.heightPt * PAGE_RENDER_SCALE).toInt(),
                                density,
                                config.contentTimeout
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.e(e) { "Failed to render page ${index + 1}: ${e.message}" }
                            return@withContext PdfResult.Error.RenderingFailed(
                                "Failed to render page ${index + 1}: ${e.message}",
                                e
                            )
                        }

                        // CoreGraphics draws images upright with a bottom-left origin, matching PDF space
                        val pageBounds = CGRectMake(0.0, 0.0, plannedPage.widthPt.toDouble(), plannedPage.heightPt.toDouble())
                        CGContextBeginPage(context, pageBounds)
                        CGContextDrawImage(context, pageBounds, uiImage.CGImage)
                        CGContextEndPage(context)
                    }
                    ensureActive()
                    allPagesWritten = true
                } finally {
                    CGPDFContextClose(context)
                    CGContextRelease(context)
                    if (!allPagesWritten) {
                        fileManager.removeItemAtPath(tempPath, error = null)
                    }
                }

                fileManager.removeItemAtPath(outputPath, error = null)
                if (!fileManager.moveItemAtPath(tempPath, toPath = outputPath, error = null)) {
                    fileManager.removeItemAtPath(tempPath, error = null)
                    return@withContext PdfResult.Error.IOError("Failed to move PDF into place at $outputPath")
                }

                // Get file size
                val fileAttributes = fileManager.attributesOfItemAtPath(outputPath, error = null)
                val fileSize = (fileAttributes?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L

                logger.logInfo { "PDF generation successful: $outputPath (${plannedPages.size} pages, $fileSize bytes)" }
                PdfResult.Success(
                    uri = outputPath,
                    filePath = outputPath,
                    fileSize = fileSize,
                    pageCount = plannedPages.size
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Failed to generate PDF: ${e.message}" }
                PdfResult.Error.Unknown("Failed to generate PDF: ${e.message}", e)
            }
        }
    }

    private fun createPdfContext(path: String, widthPt: Double, heightPt: Double, metadata: PdfMetadata): CGContextRef? {
        // CoreGraphics always writes its own producer, so only these fields can be set
        val info = NSMutableDictionary()
        metadata.title?.let { info.setObject(it, forKey = bridgedKey(kCGPDFContextTitle)) }
        metadata.author?.let { info.setObject(it, forKey = bridgedKey(kCGPDFContextAuthor)) }
        metadata.subject?.let { info.setObject(it, forKey = bridgedKey(kCGPDFContextSubject)) }
        metadata.keywords?.let { info.setObject(it, forKey = bridgedKey(kCGPDFContextKeywords)) }
        metadata.creator?.let { info.setObject(it, forKey = bridgedKey(kCGPDFContextCreator)) }

        @Suppress("UNCHECKED_CAST")
        val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef
        @Suppress("UNCHECKED_CAST")
        val auxiliaryInfo = CFBridgingRetain(info) as CFDictionaryRef
        try {
            return CGPDFContextCreateWithURL(url, CGRectMake(0.0, 0.0, widthPt, heightPt), auxiliaryInfo)
        } finally {
            CFRelease(url)
            CFRelease(auxiliaryInfo)
        }
    }

    /** A CoreGraphics dictionary key as an NSString, retained so bridging doesn't release the constant. */
    private fun bridgedKey(key: CFStringRef?): NSString = CFBridgingRelease(CFRetain(key)) as NSString

    private suspend fun renderPage(
        content: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int,
        density: Density,
        contentTimeout: Duration
    ): UIImage {
        val image = renderPageImage(content, widthPx, heightPx, density, contentTimeout)
        try {
            return image.toUIImage()
        } finally {
            image.close()
        }
    }

    private fun outputPath(fileName: String): String {
        // Get documents directory
        val documentsPath = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull() as? String ?: throw IllegalStateException("Could not find documents directory")

        val pdfDir = "$documentsPath/pdfs"
        val fileManager = NSFileManager.defaultManager

        // Create directory if needed
        if (!fileManager.fileExistsAtPath(pdfDir)) {
            fileManager.createDirectoryAtPath(
                pdfDir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        }

        return "$pdfDir/$fileName"
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun Image.toUIImage(): UIImage {
    val bytes = this.encodeToData()?.bytes ?: throw IllegalStateException("Failed to encode image")
    val nsData = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    return UIImage.imageWithData(nsData) ?: throw IllegalStateException("Failed to create UIImage")
}
