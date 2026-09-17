package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
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
            val pageContents = pageScope.pages

            if (pageContents.isEmpty()) {
                return@withContext PdfResult.Error.Unknown("No pages provided")
            }

            // Page dimensions in points
            val widthPt = config.pageSize.width.value
            val heightPt = config.pageSize.height.value

            config.margins.contentAreaError(widthPt, heightPt)?.let { message ->
                return@withContext PdfResult.Error.Unknown(message)
            }

            // Use 2x scale for rendering quality
            val scale = 2f
            val pageWidthPx = (widthPt * scale).toInt()
            val pageHeightPx = (heightPt * scale).toInt()

            logger.logDebug { "Page size: ${widthPt}x${heightPt}pt (${pageWidthPx}x${pageHeightPx}px at ${scale}x)" }
            logger.logDebug { "Rendering ${pageContents.size} pages" }

            // Create PDF
            withContext(Dispatchers.IO) {
                val document = PDDocument()

                try {
                    // Render each page
                    pageContents.forEachIndexed { index, pageContent ->
                        logger.logDebug { "Rendering page ${index + 1} of ${pageContents.size}" }

                        val bufferedImage = try {
                            renderComposableToBufferedImage(
                                content = { PageRoot(pageContent, config.margins) },
                                width = pageWidthPx,
                                height = pageHeightPx,
                                density = Density(scale)
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
                        val page = PDPage(PDRectangle(widthPt, heightPt))
                        document.addPage(page)

                        val pdImage = LosslessFactory.createFromImage(document, bufferedImage)

                        // Draw the image (PDF coordinates start from bottom-left)
                        PDPageContentStream(document, page).use { contentStream ->
                            contentStream.drawImage(
                                pdImage,
                                0f,
                                0f,
                                widthPt,
                                heightPt
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

                    logger.logInfo { "PDF generation successful: ${outputFile.absolutePath} (${pageContents.size} pages, $fileSize bytes)" }
                    PdfResult.Success(
                        uri = outputFile.absolutePath,
                        filePath = outputFile.absolutePath,
                        fileSize = fileSize,
                        pageCount = pageContents.size
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
        density: Density
    ): BufferedImage = withContext(Dispatchers.Main) {
        // Use Compose's ImageComposeScene for rendering
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = density,
            content = content
        )

        try {
            val image = scene.render()
            try {
                skiaImageToBufferedImage(image)
            } finally {
                image.close()
            }
        } finally {
            scene.close()
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
