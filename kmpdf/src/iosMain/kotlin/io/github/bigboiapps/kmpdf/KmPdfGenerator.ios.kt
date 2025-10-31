package io.github.bigboiapps.kmpdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.alexzhirkevich.qrose.options.*
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.UIKit.*

actual fun createKmPdfGenerator(): KmPdfGenerator = IosKmPdfGenerator()

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosKmPdfGenerator : KmPdfGenerator {
    override suspend fun generatePdf(content: PdfContent, fileName: String): PdfResult {
        return withContext(Dispatchers.IO) {
            try {
                val pdfData = NSMutableData()
                UIGraphicsBeginPDFContextToData(pdfData, CGRectMake(0.0, 0.0, 595.0, 842.0), null)
                UIGraphicsBeginPDFPage()

                val context = UIGraphicsGetCurrentContext() ?: return@withContext PdfResult.Error("Failed to get graphics context")

                var yPosition = 80.0

                NSString.create(string = content.title).drawAtPoint(
                    CGPointMake(50.0, yPosition),
                    withAttributes = mapOf<Any?, Any?>(
                        NSFontAttributeName to UIFont.boldSystemFontOfSize(24.0),
                        NSForegroundColorAttributeName to UIColor.blackColor
                    )
                )
                yPosition += 40.0

                content.subtitle?.let {
                    NSString.create(string = it).drawAtPoint(
                        CGPointMake(50.0, yPosition),
                        withAttributes = mapOf<Any?, Any?>(
                            NSFontAttributeName to UIFont.systemFontOfSize(16.0),
                            NSForegroundColorAttributeName to UIColor.blackColor
                        )
                    )
                    yPosition += 40.0
                }

                yPosition += 20.0

                CGContextSetFillColorWithColor(context, UIColor.whiteColor.CGColor)
                CGContextFillRect(context, CGRectMake((595.0 - 400.0) / 2.0, yPosition, 400.0, 400.0))

                yPosition += 420.0

                if (content.additionalInfo.isNotEmpty()) {
                    yPosition += 20.0
                    content.additionalInfo.forEach { info ->
                        NSString.create(string = info).drawAtPoint(
                            CGPointMake(50.0, yPosition),
                            withAttributes = mapOf<Any?, Any?>(
                                NSFontAttributeName to UIFont.systemFontOfSize(14.0),
                                NSForegroundColorAttributeName to UIColor.blackColor
                            )
                        )
                        yPosition += 25.0
                    }
                }

                content.footer?.let {
                    NSString.create(string = it).drawAtPoint(
                        CGPointMake(50.0, 842.0 - 50.0),
                        withAttributes = mapOf<Any?, Any?>(
                            NSFontAttributeName to UIFont.systemFontOfSize(12.0),
                            NSForegroundColorAttributeName to UIColor.grayColor
                        )
                    )
                }

                UIGraphicsEndPDFContext()

                val documentsPath = NSSearchPathForDirectoriesInDomains(
                    NSDocumentDirectory,
                    NSUserDomainMask,
                    true
                ).firstOrNull() as? String ?: return@withContext PdfResult.Error("Failed to get documents directory")

                val filePath = "$documentsPath/$fileName"
                val fileUrl = NSURL.fileURLWithPath(filePath)

                val success = pdfData.writeToURL(fileUrl, true)

                if (success) {
                    PdfResult.Success(filePath)
                } else {
                    PdfResult.Error("Failed to write PDF file")
                }
            } catch (e: Exception) {
                PdfResult.Error("Failed to generate PDF", e)
            }
        }
    }
}

@Composable
actual fun rememberQrCodeBitmap(data: String, size: Int): ImageBitmap? {
    val qrPainter = rememberQrCodePainter(data) {
        colors {
            dark = QrBrush.solid(Color.Black)
            light = QrBrush.solid(Color.White)
        }
        shapes {
            ball = QrBallShape.circle()
            darkPixel = QrPixelShape.roundCorners()
            frame = QrFrameShape.roundCorners(.25f)
        }
    }

    return remember(data, size) {
        try {
            val bitmap = Bitmap()
            val imageInfo = ImageInfo(
                width = size,
                height = size,
                colorType = ColorType.RGBA_8888,
                alphaType = ColorAlphaType.PREMUL
            )
            bitmap.allocPixels(imageInfo)
            bitmap.asComposeImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
