package io.github.bigboyapps.kmpdf

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import co.touchlab.kermit.Logger
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRect
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
import platform.UIKit.UIGraphicsBeginPDFContextToData
import platform.UIKit.UIGraphicsBeginPDFContextToFile
import platform.UIKit.UIGraphicsBeginPDFPageWithInfo
import platform.UIKit.UIGraphicsEndPDFContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage

private val logger = Logger.withTag("KmPdfGenerator")

actual fun createKmPdfGenerator(): KmPdfGenerator = IosKmPdfGenerator()

actual fun sharePdf(uri: String, title: String) {
    val url = NSURL.fileURLWithPath(uri)
    val activityController = UIActivityViewController(
        activityItems = listOf(url),
        applicationActivities = null
    )

    val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController
    rootViewController?.presentViewController(
        activityController,
        animated = true,
        completion = null
    )
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        pages: PdfPageScope.() -> Unit
    ): PdfResult {
        logger.logDebug { "Starting PDF generation: ${config.fileName}" }

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

                // Render each page to an image
                val pageImages = pageContents.map { pageContent ->
                    val scene = ImageComposeScene(
                        width = widthPx,
                        height = heightPx,
                        density = Density(scale.toFloat())
                    ) {
                        pageContent()
                    }
                    scene.render()
                }

                // Move to IO dispatcher for file operations
                withContext(Dispatchers.IO) {
                    // Get documents directory
                    val documentsPath = NSSearchPathForDirectoriesInDomains(
                        NSDocumentDirectory,
                        NSUserDomainMask,
                        true
                    ).firstOrNull() as? String ?: throw Exception("Could not find documents directory")

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

                    val outputPath = "$pdfDir/${config.fileName}"

                    // Create PDF
                    memScoped {
                        val bounds = CGRectMake(0.0, 0.0, widthPt, heightPt)
                        UIGraphicsBeginPDFContextToFile(outputPath, bounds, null)

                        // Render each page
                        pageImages.forEach { pageImage ->
                            UIGraphicsBeginPDFPageWithInfo(bounds, null)

                            val context = UIGraphicsGetCurrentContext()
                                ?: throw Exception("Failed to get graphics context")

                            // Convert Skia Image to UIImage
                            val uiImage = pageImage.toUIImage()

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

                        UIGraphicsEndPDFContext()
                    }

                    // Get file size
                    val fileAttributes = fileManager.attributesOfItemAtPath(outputPath, error = null)
                    val fileSize = (fileAttributes?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L

                    logger.logInfo { "PDF generation successful: $outputPath (${pageImages.size} pages, $fileSize bytes)" }
                    PdfResult.Success(
                        uri = outputPath,
                        filePath = outputPath,
                        fileSize = fileSize,
                        pageCount = pageImages.size
                    )
                }
            } catch (e: Exception) {
                logger.e(e) { "Failed to generate PDF: ${e.message}" }
                PdfResult.Error.Unknown("Failed to generate PDF: ${e.message}", e)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun Image.toUIImage(): UIImage {
    val bytes = this.encodeToData()?.bytes ?: throw Exception("Failed to encode image")
    val nsData = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    return UIImage.imageWithData(nsData) ?: throw Exception("Failed to create UIImage")
}
