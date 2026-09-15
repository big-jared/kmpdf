package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginPDFContextToFile
import platform.UIKit.UIGraphicsBeginPDFPageWithInfo
import platform.UIKit.UIGraphicsEndPDFContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController

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

        // Everything runs on the main thread: Compose rendering requires it, and the UIGraphics
        // PDF context belongs to the thread that created it
        return withContext(Dispatchers.Main) {
            try {
                // Build pages
                val pageScope = PdfPageScope()
                pageScope.pages()
                val pageContents = pageScope.pages

                if (pageContents.isEmpty()) {
                    return@withContext PdfResult.Error.Unknown("No pages defined")
                }

                // Page dimensions in points
                val widthPt = config.pageSize.width.value.toDouble()
                val heightPt = config.pageSize.height.value.toDouble()

                // Use 2x scale for rendering quality
                val scale = 2.0
                val widthPx = (widthPt * scale).toInt()
                val heightPx = (heightPt * scale).toInt()

                logger.logDebug { "Rendering ${pageContents.size} pages at ${widthPt}x${heightPt}pt" }

                val outputPath = outputPath(config.fileName)
                val bounds = CGRectMake(0.0, 0.0, widthPt, heightPt)

                if (!UIGraphicsBeginPDFContextToFile(outputPath, bounds, null)) {
                    return@withContext PdfResult.Error.IOError("Failed to create PDF file at $outputPath")
                }

                try {
                    // Render and write one page at a time so only one page image is in memory
                    pageContents.forEachIndexed { index, pageContent ->
                        logger.logDebug { "Rendering page ${index + 1} of ${pageContents.size}" }

                        val uiImage = try {
                            renderPage(pageContent, widthPx, heightPx, scale)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.e(e) { "Failed to render page ${index + 1}: ${e.message}" }
                            return@withContext PdfResult.Error.RenderingFailed(
                                "Failed to render page ${index + 1}: ${e.message}",
                                e
                            )
                        }

                        UIGraphicsBeginPDFPageWithInfo(bounds, null)

                        val context = UIGraphicsGetCurrentContext()
                            ?: throw IllegalStateException("Failed to get graphics context")

                        // Save graphics state
                        CGContextSaveGState(context)

                        uiImage.CGImage?.let { img ->
                            // Flip coordinate system for image drawing
                            // PDF origin is at bottom-left, images render top-to-bottom
                            CGContextTranslateCTM(context, 0.0, heightPt)
                            CGContextScaleCTM(context, 1.0, -1.0)

                            // Draw the full page image
                            val drawRect = CGRectMake(0.0, 0.0, widthPt, heightPt)
                            CGContextDrawImage(context, drawRect, img)
                        }

                        // Restore graphics state
                        CGContextRestoreGState(context)
                    }
                } finally {
                    UIGraphicsEndPDFContext()
                }

                // Get file size
                val fileAttributes = NSFileManager.defaultManager.attributesOfItemAtPath(outputPath, error = null)
                val fileSize = (fileAttributes?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L

                logger.logInfo { "PDF generation successful: $outputPath (${pageContents.size} pages, $fileSize bytes)" }
                PdfResult.Success(
                    uri = outputPath,
                    filePath = outputPath,
                    fileSize = fileSize,
                    pageCount = pageContents.size
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Failed to generate PDF: ${e.message}" }
                PdfResult.Error.Unknown("Failed to generate PDF: ${e.message}", e)
            }
        }
    }

    private fun renderPage(
        content: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int,
        scale: Double
    ): UIImage {
        val scene = ImageComposeScene(
            width = widthPx,
            height = heightPx,
            density = Density(scale.toFloat()),
            content = content
        )
        try {
            val image = scene.render()
            try {
                return image.toUIImage()
            } finally {
                image.close()
            }
        } finally {
            scene.close()
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
