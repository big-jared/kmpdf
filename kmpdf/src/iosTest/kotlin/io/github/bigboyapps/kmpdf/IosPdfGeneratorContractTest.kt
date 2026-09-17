package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import io.github.bigboyapps.kmpdf.testing.RgbaImage
import io.github.bigboyapps.kmpdf.testing.renderWithImageComposeScene
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGPDFDocumentCreateWithProvider
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGPDFDocumentCreateWithURL
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGPDFMediaBox
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSRunLoop
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.create
import platform.Foundation.runMode
import kotlin.concurrent.AtomicReference
import kotlin.math.floor
import kotlin.test.BeforeTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** Runs the shared generator contract on iOS, reading PDFs back with CoreGraphics. */
@OptIn(ExperimentalForeignApi::class)
class IosPdfGeneratorContractTest : PdfGeneratorContract() {
    private val pdfDirectory: String
        get() = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String) + "/pdfs"

    @BeforeTest
    fun removeOldContractFiles() {
        val fileManager = NSFileManager.defaultManager
        fileManager.contentsOfDirectoryAtPath(pdfDirectory, error = null)
            ?.map { it as String }
            ?.filter { it.startsWith("contract-") }
            ?.forEach { fileManager.removeItemAtPath("$pdfDirectory/$it", error = null) }
    }

    /**
     * Tests run on the main thread, but the generator needs the main queue for rendering. So the test
     * body runs on a background dispatcher while the main run loop keeps draining the main queue.
     */
    override fun runPdfTest(block: suspend CoroutineScope.() -> Unit): TestResult {
        val outcome = AtomicReference<Result<Unit>?>(null)
        CoroutineScope(Dispatchers.Default).launch {
            outcome.value = runCatching { coroutineScope { block() } }
        }
        val deadline = TimeSource.Monotonic.markNow() + 5.minutes
        while (outcome.value == null) {
            check(deadline.hasNotPassedNow()) { "Test timed out after 5 minutes" }
            NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        outcome.value!!.getOrThrow()
    }

    override fun createGenerator(): KmPdfGenerator = IosKmPdfGenerator()

    override suspend fun readBack(result: PdfResult.Success, renderPages: Boolean): ReadBackDocument =
        withContext(Dispatchers.Main) {
            @Suppress("UNCHECKED_CAST")
            val url = CFBridgingRetain(NSURL.fileURLWithPath(result.filePath)) as CFURLRef
            val document = CGPDFDocumentCreateWithURL(url)
            CFRelease(url)
            checkNotNull(document) { "CoreGraphics couldn't open ${result.filePath}" }
            try {
                val count = CGPDFDocumentGetNumberOfPages(document).toInt()
                val sizes = mutableListOf<Pair<Float, Float>>()
                val pages = mutableListOf<RgbaImage>()
                for (number in 1..count) {
                    val page = checkNotNull(CGPDFDocumentGetPage(document, number.toULong())) { "Missing page $number" }
                    val (width, height) = CGPDFPageGetBoxRect(page, kCGPDFMediaBox).useContents {
                        size.width.toFloat() to size.height.toFloat()
                    }
                    sizes += width to height
                    if (renderPages) pages += renderPdfPage(page, width, height)
                }
                ReadBackDocument(sizes, pages)
            } finally {
                CGPDFDocumentRelease(document)
            }
        }

    private fun renderPdfPage(page: platform.CoreGraphics.CGPDFPageRef, widthPt: Float, heightPt: Float): RgbaImage {
        val widthPx = floor(widthPt * RENDER_SCALE).toInt()
        val heightPx = floor(heightPt * RENDER_SCALE).toInt()
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            data = null,
            width = widthPx.toULong(),
            height = heightPx.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (widthPx * 4).toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        )
        CGColorSpaceRelease(colorSpace)
        checkNotNull(context) { "Couldn't create a bitmap context" }
        try {
            CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(context, CGRectMake(0.0, 0.0, widthPx.toDouble(), heightPx.toDouble()))
            CGContextScaleCTM(context, RENDER_SCALE.toDouble(), RENDER_SCALE.toDouble())
            CGContextDrawPDFPage(context, page)
            val data = checkNotNull(CGBitmapContextGetData(context)) { "Bitmap context has no data" }
            // The page was drawn over opaque white, so premultiplied and straight alpha are the same here
            return RgbaImage(widthPx, heightPx, data.reinterpret<UByteVar>().readBytes(widthPx * heightPx * 4))
        } finally {
            CGContextRelease(context)
        }
    }

    override suspend fun renderReference(content: @Composable () -> Unit, widthPx: Int, heightPx: Int): RgbaImage =
        withContext(Dispatchers.Main) {
            renderWithImageComposeScene(content, widthPx, heightPx, RENDER_SCALE.toFloat())
        }

    override fun outputExists(fileName: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath("$pdfDirectory/$fileName")

    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    override suspend fun pageCountOf(bytes: ByteArray): Int {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        @Suppress("UNCHECKED_CAST")
        val cfData = CFBridgingRetain(data) as CFDataRef
        val provider = checkNotNull(CGDataProviderCreateWithCFData(cfData)) { "Couldn't create a data provider" }
        val document = CGPDFDocumentCreateWithProvider(provider)
        try {
            return checkNotNull(document) { "CoreGraphics couldn't open the PDF bytes" }
                .let { CGPDFDocumentGetNumberOfPages(it).toInt() }
        } finally {
            CGPDFDocumentRelease(document)
            CGDataProviderRelease(provider)
            CFRelease(cfData)
        }
    }
}
