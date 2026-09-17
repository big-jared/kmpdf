package io.github.bigboyapps.kmpdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The root every platform renders page content in.
 *
 * Platforms hand their root content different constraints: Android's ComposeView uses the exact page
 * size, while Compose's offscreen scene allows anything up to it. Without a common root, something like
 * `Modifier.size(50.dp)` fills the whole page on Android only. The Box gives page content the same
 * loose constraints everywhere.
 */
@Composable
internal fun PageRoot(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
    }
}
