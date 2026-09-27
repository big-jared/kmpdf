package io.github.bigboyapps.kmpdf.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bigboyapps.kmpdf.PdfContentLoading
import kotlinx.coroutines.delay

/** A labelled case on a showcase page, so each PDF explains what it's demonstrating. */
@Composable
private fun Case(title: String, note: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(note, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).background(Color(0xFFF3F4F6)).padding(8.dp)) { content() }
    }
}

/**
 * Text that's awkward to place in a PDF: several scripts, wrapping, indentation, alignment, padding
 * after a semantics modifier, and text that's clipped or ellipsized. Everything visible here can be
 * selected, searched, and copied from the PDF; the hidden words can't, because they aren't in it.
 */
@Composable
fun SelectableTextShowcase() {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Selectable text", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Open the PDF and try selecting, searching (Cmd/Ctrl+F), and copying.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Case("Scripts and symbols", "Accents, CJK, Korean, Greek, and emoji all copy back exactly.") {
            Column {
                Text("Invoice #1042 (paid) – total \\ fees")
                Text("Résumé, naïve café, déjà vu, Ærøskøbing")
                Text("日本語のテキスト · 中文文本 · 한국어 텍스트 · Ελληνικά")
                Text("Emoji stay one character each: 🎉 ✅ 📄")
            }
        }

        Case("Wrapped paragraph", "Each wrapped line is its own line in the PDF, in reading order.") {
            Text(
                "The quick brown fox jumps over the lazy dog while the invoice total is calculated, " +
                    "the receipt is printed, and the PDF is shared.",
                modifier = Modifier.width(260.dp)
            )
        }

        Case("Leading spaces", "Spaces used for indentation aren't part of the text; selection starts at the word.") {
            Text("        Subtotal (indented with spaces)")
        }

        Case("Alignment without wrapping", "Right-aligned and centered text sits over the drawn text, not at the left edge.") {
            Column {
                Text(
                    "Total due: $1,284.50",
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    softWrap = false,
                    fontWeight = FontWeight.Bold
                )
                Text("— Thank you for your business —", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, softWrap = false)
            }
        }

        Case("Padding after a semantics modifier", "testTag(...).padding(24.dp): the text layer follows the drawn text.") {
            Text("Tagged and padded text", Modifier.testTag("tagged").padding(start = 24.dp, top = 6.dp))
        }

        Case("Hidden text stays out", "Clipped and ellipsized words aren't in the PDF at all, so searching can't find them.") {
            Column {
                Box(Modifier.size(width = 220.dp, height = 20.dp).clipToBounds()) {
                    Text("Visible line", Modifier.padding(start = 2.dp))
                    Text("HIDDENBYCLIP", Modifier.offset(y = 44.dp))
                }
                Text(
                    "This sentence is cut off with an ellipsis before HIDDENTAIL",
                    Modifier.width(220.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Case("Rotated text", "Drawn rotated; its text is still in the PDF, placed along its line.") {
            Box(Modifier.fillMaxWidth().height(40.dp)) {
                Text("Rotated 8 degrees", Modifier.rotate(8f))
            }
        }
    }
}

/**
 * Drawing that isn't text: gradients, a Canvas chart, shapes, and a generated image. It's all rendered
 * into the page, which is why pages come out looking exactly like the composable.
 */
@Composable
fun GraphicsShowcase() {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Graphics and images", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Anything Compose can draw goes into the page as pixels.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Case("Gradients and shapes", "A linear gradient, a radial gradient, and clipped shapes.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(90.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF9333EA))))
                )
                Box(
                    Modifier.size(90.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFFFACC15), Color(0xFFDC2626))))
                )
                Box(Modifier.size(90.dp).background(Color(0xFF111827)).padding(10.dp)) {
                    Box(Modifier.fillMaxWidth().height(70.dp).background(Color.White.copy(alpha = 0.35f)))
                }
            }
        }

        Case("Canvas drawing", "Lines, a filled path, and an arc drawn with the Canvas API.") {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val step = size.width / 11f
                val points = listOf(0.45f, 0.62f, 0.38f, 0.8f, 0.55f, 0.92f, 0.7f, 0.5f, 0.85f, 0.66f, 0.95f)
                val path = Path().apply {
                    moveTo(0f, size.height)
                    points.forEachIndexed { i, value -> lineTo(i * step, size.height - size.height * value) }
                    lineTo(points.lastIndex * step, size.height)
                    close()
                }
                drawPath(path, Brush.verticalGradient(listOf(Color(0xFF60A5FA), Color(0x2260A5FA))))
                points.forEachIndexed { i, value ->
                    if (i > 0) {
                        drawLine(
                            Color(0xFF1D4ED8),
                            Offset((i - 1) * step, size.height - size.height * points[i - 1]),
                            Offset(i * step, size.height - size.height * value),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
                drawArc(
                    Color(0xFFEF4444),
                    startAngle = -90f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = Offset(size.width - 90f, 10f),
                    size = androidx.compose.ui.geometry.Size(72f, 72f),
                    style = Stroke(width = 8f)
                )
            }
        }

        Case("Generated image", "An ImageBitmap built in code, drawn at three scales.") {
            val checkerboard = remember { checkerboardImage() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(48.dp, 72.dp, 110.dp).forEach { side ->
                    androidx.compose.foundation.Image(
                        painter = BitmapPainter(checkerboard),
                        contentDescription = null,
                        modifier = Modifier.size(side),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        Case("Text drawn over graphics", "Labels over a filled background are still selectable.") {
            Box(Modifier.fillMaxWidth().height(60.dp).background(Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF14B8A6))))) {
                Text(
                    "Revenue up 24% quarter over quarter",
                    Modifier.padding(12.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** A 16x16 checkerboard with a colored border, so scaling and color are easy to check in the PDF. */
private fun checkerboardImage(): ImageBitmap {
    val side = 16
    val pixels = IntArray(side * side) { index ->
        val x = index % side
        val y = index / side
        val edge = x == 0 || y == 0 || x == side - 1 || y == side - 1
        when {
            edge -> 0xFFDC2626.toInt()
            (x / 2 + y / 2) % 2 == 0 -> 0xFF1F2937.toInt()
            else -> 0xFFF9FAFB.toInt()
        }
    }
    return ImageBitmap(side, side).also { image ->
        androidx.compose.ui.graphics.Canvas(image).let { canvas ->
            val paint = androidx.compose.ui.graphics.Paint()
            for (y in 0 until side) {
                for (x in 0 until side) {
                    paint.color = Color(pixels[y * side + x])
                    canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
                }
            }
        }
    }
}

/**
 * Content that isn't ready when the page is first composed. [PdfContentLoading] holds the page until
 * the data arrives, so the PDF never captures a half-loaded page.
 *
 * @param delayMillis How long the fake "network call" takes.
 */
@Composable
fun AsyncContentShowcase(delayMillis: Long = 1_200) {
    val rows by produceState<List<Pair<String, Int>>?>(null) {
        delay(delayMillis)
        value = listOf("North" to 128, "South" to 94, "East" to 173, "West" to 61)
    }

    // Outside PDF generation this does nothing, so the same composable works on screen
    PdfContentLoading(isLoading = rows == null)

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Content that loads late", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "These rows arrive ${delayMillis}ms after the page is composed. The page waits for them.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (rows == null) {
            Text("Loading…", color = Color.Gray)
        } else {
            rows?.forEach { (region, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(region, Modifier.width(90.dp))
                    Box(Modifier.height(14.dp).width((value * 1.4f).dp).background(Color(0xFF2563EB)))
                    Text("  $value", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Loaded before capture ✓",
                Modifier.padding(top = 10.dp),
                color = Color(0xFF15803D),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** A page that never finishes loading, to show the contentTimeout error instead of a hung generator. */
@Composable
fun NeverLoadsShowcase() {
    PdfContentLoading(isLoading = true)
    Text("This page never finishes loading.", Modifier.padding(24.dp))
}

/** A page for the mixed-sizes sample, showing what size it was given. */
@Composable
fun MixedSizesPage(title: String, note: String) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFEFF6FF)).padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}

/**
 * Checks the bytes read back with `readPdfBytes` against what generation reported: the size matches, the
 * file starts with the PDF header, and it ends with the end-of-file marker.
 */
fun checkPdfBytes(bytes: ByteArray, result: io.github.bigboyapps.kmpdf.PdfResult.Success): String {
    val header = bytes.take(5).toByteArray().decodeToString()
    val tail = bytes.takeLast(32).toByteArray().decodeToString()
    val problems = buildList {
        if (bytes.size.toLong() != result.fileSize) add("size is ${bytes.size} but the result says ${result.fileSize}")
        if (header != "%PDF-") add("starts with \"$header\" instead of \"%PDF-\"")
        if (!tail.trimEnd().endsWith("%%EOF")) add("doesn't end with %%EOF")
    }
    return if (problems.isEmpty()) {
        "Read back ${bytes.size} bytes: $header, ends with %%EOF ✓"
    } else {
        "Read back ${bytes.size} bytes, but ${problems.joinToString("; ")}"
    }
}
