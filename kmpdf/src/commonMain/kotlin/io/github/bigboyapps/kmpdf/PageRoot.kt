package io.github.bigboyapps.kmpdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds

/**
 * The root every platform renders page content in.
 *
 * Platforms hand their root content different constraints: Android's ComposeView uses the exact page
 * size, while Compose's offscreen scene allows anything up to it. Without a common root, something like
 * `Modifier.size(50.dp)` fills the whole page on Android only. The outer Box gives page content the same
 * loose constraints everywhere; the inner one applies the margins and clips content to them.
 */
@Composable
internal fun PageRoot(content: @Composable () -> Unit, margins: PdfMargins = PdfMargins.None) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .absolutePadding(left = margins.left, top = margins.top, right = margins.right, bottom = margins.bottom)
                .clipToBounds()
        ) {
            content()
        }
    }
}

/** A message describing why these margins don't fit on the page, or null when they leave room for content. */
internal fun PdfMargins.contentAreaError(pageWidthPt: Float, pageHeightPt: Float): String? {
    val contentWidth = pageWidthPt - left.value - right.value
    val contentHeight = pageHeightPt - top.value - bottom.value
    return if (contentWidth > 0f && contentHeight > 0f) {
        null
    } else {
        "Margins (left ${left.value}, top ${top.value}, right ${right.value}, bottom ${bottom.value} pt) " +
            "leave no room for content on a $pageWidthPt x $pageHeightPt pt page"
    }
}
