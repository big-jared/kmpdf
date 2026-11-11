package io.github.bigboyapps.kmpdf.testing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Distinct colors used to mark pages so tests can check that pages come back in order. */
val PageMarkerColors = listOf(
    Color(0xFFE53935),
    Color(0xFF43A047),
    Color(0xFF1E88E5),
    Color(0xFFFDD835),
    Color(0xFF8E24AA),
    Color(0xFF00ACC1)
)

/** Where the marker block sits on a [MarkerPage], in dp from the top-left. */
const val MARKER_CENTER_DP = 140

/** A white page with a solid marker block, used for page order checks. */
@Composable
fun MarkerPage(color: Color) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.offset(40.dp, 40.dp).size(200.dp).background(color))
    }
}

/**
 * Dense, varied content (text, colors, translucent fills) for pixel comparisons.
 *
 * [text], [shift], and [accent] let tests build negative controls that must NOT match.
 */
@Composable
fun DetailedContent(
    text: String = "KmPDF 0123456789",
    shift: Dp = 0.dp,
    accent: Color = Color(0xFF3F51B5)
) {
    Box(Modifier.fillMaxSize().background(Color.White).padding(start = 24.dp + shift, top = 24.dp + shift)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(text, style = TextStyle(fontSize = 28.sp, color = Color.Black))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { i ->
                    Box(Modifier.size(48.dp).background(if (i % 2 == 0) accent else Color(0xFFFF9800)))
                }
            }
            repeat(8) { i ->
                Box(
                    Modifier
                        .fillMaxWidth(0.9f)
                        .height(10.dp)
                        .background(Color(0xFF009688).copy(alpha = 0.3f + i * 0.08f))
                )
            }
            BasicText(
                "The quick brown fox jumps over the lazy dog",
                style = TextStyle(fontSize = 16.sp, color = Color(0xFF424242))
            )
        }
    }
}

/** Content with no background: only a small colored square, so the rest of the page is transparent. */
@Composable
fun TransparentContent() {
    Box(Modifier.offset(20.dp, 20.dp).size(50.dp).background(Color.Red))
}

/** Content far larger than any page, with colored bands, to check it's clipped at the page edge. */
@Composable
fun OversizedContent() {
    Box(Modifier.requiredSize(3000.dp).background(Color(0xFFFFCDD2))) {
        Column {
            repeat(30) { i ->
                Box(Modifier.fillMaxWidth().height(100.dp).background(if (i % 2 == 0) Color(0xFF1565C0) else Color.White))
            }
        }
    }
}

class TestPageException : RuntimeException("Test page content failed on purpose")

/** Two stacked blocks: 123 dp of red, then 77 dp of blue, 200 dp tall in total. */
@Composable
fun StackedBlocks() {
    Column {
        Box(Modifier.fillMaxWidth().height(123.dp).background(Color.Red))
        Box(Modifier.fillMaxWidth().height(77.dp).background(Color.Blue))
    }
}

/**
 * A color that encodes an item index from 0 to 99 for pagination checks. Neighboring indexes differ by at
 * least 10 in a channel, so small color shifts from a PDF renderer can't be mistaken for another item.
 */
fun itemColor(index: Int): Color {
    require(index in 0..99) { "Item index must be 0 to 99, was $index" }
    return Color(red = index % 25 * 10, green = index / 25 * 60, blue = 128)
}

/** Decodes [itemColor], or returns null when [rgb] isn't close to an item color. */
fun decodeItemIndex(rgb: Rgb): Int? {
    val low = (rgb.r + 5) / 10
    val high = (rgb.g + 30) / 60
    val index = high * 25 + low
    if (low !in 0..24 || high !in 0..3) return null
    return index.takeIf { rgb.distanceTo(Rgb(low * 10, high * 60, 128)) <= 4 }
}

/** A color that encodes a page number and page count, for footer checks. */
fun footerColor(pageNumber: Int, pageCount: Int): Color = Color(red = pageNumber * 20, green = pageCount * 20, blue = 255)

/** Plain text, like Material's Text, without depending on Material. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    color: Color = Color.Black,
    textAlign: TextAlign = TextAlign.Unspecified,
    softWrap: Boolean = true
) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = fontSize, textAlign = textAlign), softWrap = softWrap)
}

/** Text in several scripts, plus a paragraph that wraps onto several lines. */
@Composable
fun TextSamples() {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(TextSamples.INVOICE, fontSize = 20.sp)
        Text(TextSamples.ACCENTS)
        Text(TextSamples.CJK)
        Text(TextSamples.PARAGRAPH, modifier = Modifier.width(160.dp))
    }
}

object TextSamples {
    const val INVOICE = "Invoice #1042"
    const val ACCENTS = "Résumé naïve café – déjà vu"
    const val CJK = "日本語のテキスト"
    const val PARAGRAPH = "The quick brown fox jumps over the lazy dog while the invoice total is calculated"

    /** What each sample reads as once extracted, with whitespace collapsed. */
    val phrases = listOf(INVOICE, ACCENTS, CJK, PARAGRAPH)
}
