package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import kotlin.time.Duration

private val logger = Logger.withTag("KmPdfGenerator")

actual fun createKmPdfGenerator(): KmPdfGenerator = DesktopKmPdfGenerator()

actual fun sharePdf(uri: String, title: String) {
    try {
        val file = File(uri)
        if (file.exists() && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        }
    } catch (e: Exception) {
        logger.e(e) { "Failed to open PDF: ${e.message}" }
    }
}

actual suspend fun readPdfBytes(uri: String): ByteArray = withContext(Dispatchers.IO) {
    val file = if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
    file.readBytes()
}

class DesktopKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(
        config: PdfConfig,
        pages: PdfPageScope.() -> Unit
    ): PdfResult = withContext(Dispatchers.Default) {
        generatePdfWith(DesktopPdfPlatform, config, pages, logger)
    }
}

/** Renders with Skia on the UI thread and saves to `outputDirectory` or `~/Documents/pdfs`. */
private object DesktopPdfPlatform : SkiaPdfPlatform() {
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

    override suspend fun compress(data: ByteArray): ByteArray = withContext(Dispatchers.Default) { zlibCompress(data) }

    override suspend fun save(pdf: ByteArray, config: PdfConfig, pageCount: Int): PdfResult.Success =
        withContext(Dispatchers.IO) {
            val outputDir = if (config.outputDirectory != null) {
                File(config.outputDirectory)
            } else {
                File(System.getProperty("user.home"), "Documents/pdfs")
            }
            outputDir.mkdirs()
            val outputFile = File(outputDir, config.fileName)
            // Write to a temporary file first so an earlier PDF is only replaced by a complete one
            val tempFile = File(outputDir, "${config.fileName}.partial")
            try {
                tempFile.writeBytes(pdf)
                Files.move(tempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tempFile.delete()
            }
            PdfResult.Success(
                uri = outputFile.absolutePath,
                filePath = outputFile.absolutePath,
                fileSize = outputFile.length(),
                pageCount = pageCount
            )
        }
}

private fun zlibCompress(data: ByteArray): ByteArray {
    val deflater = Deflater()
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 4)
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}
