package io.github.bigboyapps.kmpdf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopKmPdfGeneratorTest {
    private val outputDir = Files.createTempDirectory("kmpdf-test").toFile()

    private fun config(fileName: String) = PdfConfig(fileName = fileName, outputDirectory = outputDir.absolutePath)

    @AfterTest
    fun cleanUp() {
        outputDir.deleteRecursively()
    }

    @Test
    fun writesOnePdfPagePerComposablePage() = runBlocking {
        val result = DesktopKmPdfGenerator().generatePdf(config("two-pages.pdf")) {
            page { Box(Modifier.fillMaxSize().background(Color.Red)) }
            page { BasicText("Page 2") }
        }

        assertIs<PdfResult.Success>(result)
        assertEquals(2, result.pageCount)
        Loader.loadPDF(File(result.filePath)).use { document ->
            assertEquals(2, document.numberOfPages)
            assertEquals(595f, document.getPage(0).mediaBox.width)
        }
    }

    @Test
    fun reportsRenderingFailedWhenPageContentThrows() = runBlocking {
        val result = DesktopKmPdfGenerator().generatePdf(config("broken.pdf")) {
            page { error("page content exploded") }
        }

        assertIs<PdfResult.Error.RenderingFailed>(result)
        assertTrue(result.message.contains("page content exploded"), result.message)
    }

    @Test
    fun propagatesCancellationInsteadOfReturningAnError() = runBlocking {
        var result: PdfResult? = null
        lateinit var job: Job
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            result = DesktopKmPdfGenerator().generatePdf(config("cancelled.pdf")) {
                page {
                    // Cancel while the page is being rendered on the main dispatcher
                    job.cancel()
                    BasicText("Cancelled")
                }
            }
        }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(result)
    }
}
