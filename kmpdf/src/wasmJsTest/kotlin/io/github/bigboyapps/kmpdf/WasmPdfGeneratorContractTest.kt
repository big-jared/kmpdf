@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import io.github.bigboyapps.kmpdf.testing.PdfStructure
import io.github.bigboyapps.kmpdf.testing.RgbaImage
import io.github.bigboyapps.kmpdf.testing.renderWithImageComposeScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toInt8Array
import kotlin.js.Promise
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the shared generator contract in the browser.
 *
 * Browsers can't render PDFs from script, so the file is validated with the strict structure reader
 * and each page image is decompressed and compared pixel for pixel.
 */
class WasmPdfGeneratorContractTest : PdfGeneratorContract() {
    override fun runPdfTest(block: suspend CoroutineScope.() -> Unit): TestResult =
        runTest(timeout = 5.minutes) { block() }

    override fun createGenerator(): KmPdfGenerator = WasmKmPdfGenerator()

    override suspend fun readBack(result: PdfResult.Success, renderPages: Boolean): ReadBackDocument {
        val bytes = readPdfBytes(result.uri)
        assertEquals(result.fileSize, bytes.size.toLong(), "Blob size should match fileSize")
        val structure = PdfStructure.parse(bytes)
        val sizes = structure.pages.map { it.widthPt to it.heightPt }
        val pages = if (renderPages) {
            structure.pages.indices.map { index ->
                val image = structure.images(index).single()
                assertEquals("DeviceRGB", image.colorSpace)
                assertEquals(8, image.bitsPerComponent)
                val rgb = when (image.filters) {
                    emptyList<String>() -> image.data
                    listOf("FlateDecode") -> inflate(image.data.toInt8Array()).await<Int8Array>().toByteArray()
                    else -> error("Unexpected image filters ${image.filters}")
                }
                RgbaImage.fromRgb(image.width, image.height, rgb)
            }
        } else {
            emptyList()
        }
        return ReadBackDocument(sizes, pages)
    }

    override suspend fun renderReference(content: @Composable () -> Unit, widthPx: Int, heightPx: Int): RgbaImage =
        renderWithImageComposeScene(content, widthPx, heightPx, RENDER_SCALE.toFloat())

    // Browsers have no file system; generated PDFs only exist as blob URLs
    override fun outputExists(fileName: String): Boolean = false

    override suspend fun pageCountOf(bytes: ByteArray): Int = PdfStructure.parse(bytes).pages.size

    override suspend fun readMetadata(result: PdfResult.Success): Map<String, String> =
        PdfStructure.parse(readPdfBytes(result.uri)).info
}

@JsFun(
    """(bytes) => new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate')))
        .arrayBuffer()
        .then((buffer) => new Int8Array(buffer))"""
)
private external fun inflate(bytes: Int8Array): Promise<JsAny?>
