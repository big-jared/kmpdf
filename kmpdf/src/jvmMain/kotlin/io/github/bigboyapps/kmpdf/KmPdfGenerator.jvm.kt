package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
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
    ): PdfResult {
        logger.logDebug { "Starting PDF generation: ${config.fileName}" }

        return withContext(Dispatchers.Default) {
            // Build page list
            val pageScope = PdfPageScope()
            pageScope.pages()

            if (pageScope.pages.isEmpty()) {
                return@withContext PdfResult.Error.Unknown("No pages provided")
            }

            val density = Density(PAGE_RENDER_SCALE)
            val plannedPages = try {
                planPages(config, pageScope.pages) { contents, widthPx ->
                    withContext(Dispatchers.Main) {
                        measureContentHeightsPx(contents, widthPx, density, config.contentTimeout)
                    }
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

            // Create PDF
            withContext(Dispatchers.IO) {
                val document = PDDocument()

                try {
                    // Render each page
                    plannedPages.forEachIndexed { index, plannedPage ->
                        logger.logDebug { "Rendering page ${index + 1} of ${plannedPages.size}" }

                        val bufferedImage = try {
                            renderComposableToBufferedImage(
                                content = plannedPage.content,
                                width = (plannedPage.widthPt * PAGE_RENDER_SCALE).toInt(),
                                height = (plannedPage.heightPt * PAGE_RENDER_SCALE).toInt(),
                                density = density,
                                contentTimeout = config.contentTimeout
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

                        // Create PDF page
                        val page = PDPage(PDRectangle(plannedPage.widthPt, plannedPage.heightPt))
                        document.addPage(page)

                        val pdImage = LosslessFactory.createFromImage(document, bufferedImage)

                        // Draw the image (PDF coordinates start from bottom-left)
                        PDPageContentStream(document, page).use { contentStream ->
                            contentStream.drawImage(
                                pdImage,
                                0f,
                                0f,
                                plannedPage.widthPt,
                                plannedPage.heightPt
                            )
                        }
                    }

                    // Save to file
                    val outputDir = if (config.outputDirectory != null) {
                        File(config.outputDirectory)
                    } else {
                        File(System.getProperty("user.home"), "Documents/pdfs")
                    }.apply {
                        mkdirs()
                    }
                    val outputFile = File(outputDir, config.fileName)
                    document.save(outputFile)

                    val fileSize = outputFile.length()

                    logger.logInfo { "PDF generation successful: ${outputFile.absolutePath} (${plannedPages.size} pages, $fileSize bytes)" }
                    PdfResult.Success(
                        uri = outputFile.absolutePath,
                        filePath = outputFile.absolutePath,
                        fileSize = fileSize,
                        pageCount = plannedPages.size
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(e) { "Failed to generate PDF: ${e.message}" }
                    PdfResult.Error.IOError("Failed to generate PDF: ${e.message}", e)
                } finally {
                    document.close()
                }
            }
        }
    }

    private suspend fun renderComposableToBufferedImage(
        content: @Composable () -> Unit,
        width: Int,
        height: Int,
        density: Density,
        contentTimeout: Duration
    ): BufferedImage = withContext(Dispatchers.Main) {
        val image = renderPageImage(content, width, height, density, contentTimeout)
        try {
            skiaImageToBufferedImage(image)
        } finally {
            image.close()
        }
    }

    private fun skiaImageToBufferedImage(image: Image): BufferedImage {
        // Encode to PNG and decode to BufferedImage (simplest cross-platform approach)
        val data = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            ?: throw IllegalStateException("Failed to encode rendered page")
        return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data.bytes))
            ?: throw IllegalStateException("Failed to decode rendered page")
    }
}
