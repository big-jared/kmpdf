package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController
import platform.posix.memcpy
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.compressBound
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

class IosKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        pages: PdfPageScope.() -> Unit
    ): PdfResult = generatePdfWith(IosPdfPlatform, config, pages, logger)
}

/** Renders with Skia on the main thread, compresses with zlib, and saves to `Documents/pdfs`. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private object IosPdfPlatform : SkiaPdfPlatform() {
    override suspend fun measureContentHeightsPx(
        contents: List<@Composable () -> Unit>,
        widthPx: Int,
        contentTimeout: Duration
    ): List<Int> = withContext(Dispatchers.Main) { super.measureContentHeightsPx(contents, widthPx, contentTimeout) }

    override suspend fun renderPage(
        content: @Composable () -> Unit,
        widthPx: Int,
        heightPx: Int,
        contentTimeout: Duration
    ): RenderedPage = withContext(Dispatchers.Main) { super.renderPage(content, widthPx, heightPx, contentTimeout) }

    override suspend fun compress(data: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        memScoped {
            val bound = compressBound(data.size.convert())
            val output = ByteArray(bound.toInt())
            val outputSize = alloc<ULongVar>().apply { value = bound }
            val status = data.usePinned { input ->
                output.usePinned { out ->
                    compress2(
                        out.addressOf(0).reinterpret(),
                        outputSize.ptr,
                        input.addressOf(0).reinterpret(),
                        data.size.convert(),
                        Z_DEFAULT_COMPRESSION
                    )
                }
            }
            check(status == Z_OK) { "zlib compression failed with status $status" }
            output.copyOf(outputSize.value.toInt())
        }
    }

    override suspend fun save(pdf: ByteArray, config: PdfConfig, pageCount: Int): PdfResult.Success =
        withContext(Dispatchers.IO) {
            val outputPath = outputPath(config.fileName)
            val data = pdf.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = pdf.size.convert())
            }
            // Atomic writes go through a temporary file, so an earlier PDF is only replaced by a complete one
            check(data.writeToFile(outputPath, atomically = true)) { "Failed to write the PDF to $outputPath" }
            PdfResult.Success(
                uri = outputPath,
                filePath = outputPath,
                fileSize = pdf.size.toLong(),
                pageCount = pageCount
            )
        }

    private fun outputPath(fileName: String): String {
        val documentsPath = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull() as? String ?: throw IllegalStateException("Could not find documents directory")

        val pdfDir = "$documentsPath/pdfs"
        val fileManager = NSFileManager.defaultManager
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
