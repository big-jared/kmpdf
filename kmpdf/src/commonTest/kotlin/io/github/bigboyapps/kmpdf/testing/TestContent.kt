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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
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
