package io.github.bigboyapps.kmpdf.testing

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.bigboyapps.kmpdf.PageRoot
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** Renders [content] with Compose's offscreen scene, the same way the Skia-based generators do. */
fun renderWithImageComposeScene(content: @Composable () -> Unit, widthPx: Int, heightPx: Int, density: Float): RgbaImage {
    val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(density), content = { PageRoot(content) })
    try {
        val image = scene.render()
        try {
            return image.toRgbaImage()
        } finally {
            image.close()
        }
    } finally {
        scene.close()
    }
}

/** Reads a Skia image as straight-alpha RGBA. */
fun Image.toRgbaImage(): RgbaImage {
    val bitmap = Bitmap()
    try {
        bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        check(readPixels(bitmap, 0, 0)) { "Failed to read rendered pixels" }
        val pixels = bitmap.readPixels() ?: error("Failed to read rendered pixels")
        return RgbaImage(width, height, pixels)
    } finally {
        bitmap.close()
    }
}
