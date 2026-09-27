package io.github.bigboyapps.kmpdf.testing

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An 8-bit RGBA image, stored row by row from the top-left. */
class RgbaImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive, got ${width}x$height" }
        require(pixels.size == width * height * 4) {
            "Expected ${width * height * 4} bytes for a ${width}x$height image, got ${pixels.size}"
        }
    }

    fun pixel(x: Int, y: Int): Rgb {
        val i = (y * width + x) * 4
        return Rgb(channel(i), channel(i + 1), channel(i + 2))
    }

    /** Composites straight (non-premultiplied) alpha onto white, the way a PDF page shows it. */
    fun flattenedOnWhite(): RgbaImage {
        val out = pixels.copyOf()
        for (i in out.indices step 4) {
            val alpha = channel(i + 3)
            for (c in 0 until 3) {
                out[i + c] = ((channel(i + c) * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
            }
            out[i + 3] = 0xFF.toByte()
        }
        return RgbaImage(width, height, out)
    }

    /** The bounds of every pixel in [rows] that isn't close to white, or null when they're blank. */
    fun inkBounds(threshold: Int = 64, rows: IntRange = 0 until height): InkBounds? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in rows.first.coerceAtLeast(0)..rows.last.coerceAtMost(height - 1)) {
            for (x in 0 until width) {
                if (pixel(x, y).distanceTo(Rgb(255, 255, 255)) > threshold) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) null else InkBounds(left, top, right + 1, bottom + 1)
    }

    private fun channel(index: Int): Int = pixels[index].toInt() and 0xFF

    companion object {
        /** Builds an opaque image from RGB bytes. */
        fun fromRgb(width: Int, height: Int, rgb: ByteArray): RgbaImage {
            require(rgb.size == width * height * 3) { "Expected ${width * height * 3} RGB bytes, got ${rgb.size}" }
            val rgba = ByteArray(width * height * 4)
            var src = 0
            var dst = 0
            while (src < rgb.size) {
                rgba[dst] = rgb[src]
                rgba[dst + 1] = rgb[src + 1]
                rgba[dst + 2] = rgb[src + 2]
                rgba[dst + 3] = 0xFF.toByte()
                src += 3
                dst += 4
            }
            return RgbaImage(width, height, rgba)
        }

        /** Builds an image from ARGB ints with straight alpha, as returned by Android and AWT. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): RgbaImage {
            require(argb.size == width * height) { "Expected ${width * height} ARGB pixels, got ${argb.size}" }
            val rgba = ByteArray(width * height * 4)
            argb.forEachIndexed { index, color ->
                val i = index * 4
                rgba[i] = (color shr 16).toByte()
                rgba[i + 1] = (color shr 8).toByte()
                rgba[i + 2] = color.toByte()
                rgba[i + 3] = (color ushr 24).toByte()
            }
            return RgbaImage(width, height, rgba)
        }
    }
}

/** A pixel rectangle; [right] and [bottom] are exclusive. */
data class InkBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class Rgb(val r: Int, val g: Int, val b: Int) {
    /** The largest per-channel difference to [other]. */
    fun distanceTo(other: Rgb): Int = max(abs(r - other.r), max(abs(g - other.g), abs(b - other.b)))
}

/**
 * The result of comparing a PDF page read back from the file with a reference render.
 *
 * Different PDF engines resample images slightly differently, which spreads faint noise across the
 * page. Real content differences, even a single changed character, concentrate in a small area. So a
 * match needs a low global mean difference AND no [TILE_SIZE] x [TILE_SIZE] tile with a high mean.
 */
data class PixelComparison(
    val sizeMatches: Boolean,
    val expectedSize: String,
    val actualSize: String,
    val meanAbsoluteDifference: Double,
    val worstTileMeanDifference: Double,
    val worstTile: String,
    val differingTiles: Int
) {
    val matches: Boolean
        get() = sizeMatches &&
            meanAbsoluteDifference <= MAX_MEAN_ABSOLUTE_DIFFERENCE &&
            worstTileMeanDifference <= MAX_TILE_MEAN_DIFFERENCE

    override fun toString(): String = if (!sizeMatches) {
        "size mismatch: expected $expectedSize, actual $actualSize"
    } else {
        "mean difference $meanAbsoluteDifference (max $MAX_MEAN_ABSOLUTE_DIFFERENCE), worst tile $worstTile " +
            "mean $worstTileMeanDifference (max $MAX_TILE_MEAN_DIFFERENCE), $differingTiles differing tiles"
    }

    companion object {
        const val TILE_SIZE = 16
        const val MAX_MEAN_ABSOLUTE_DIFFERENCE = 2.0
        const val MAX_TILE_MEAN_DIFFERENCE = 16.0
    }
}

fun comparePixels(expected: RgbaImage, actual: RgbaImage): PixelComparison {
    val expectedSize = "${expected.width}x${expected.height}"
    val actualSize = "${actual.width}x${actual.height}"
    if (expected.width != actual.width || expected.height != actual.height) {
        return PixelComparison(false, expectedSize, actualSize, Double.MAX_VALUE, Double.MAX_VALUE, "-", -1)
    }

    val width = expected.width
    val height = expected.height
    val tilesX = (width + PixelComparison.TILE_SIZE - 1) / PixelComparison.TILE_SIZE
    val tilesY = (height + PixelComparison.TILE_SIZE - 1) / PixelComparison.TILE_SIZE
    val tileSums = LongArray(tilesX * tilesY)
    val tileSamples = IntArray(tilesX * tilesY)
    var totalSum = 0L

    val e = expected.pixels
    val a = actual.pixels
    for (y in 0 until height) {
        val tileRow = (y / PixelComparison.TILE_SIZE) * tilesX
        for (x in 0 until width) {
            val i = (y * width + x) * 4
            val difference = abs((e[i].toInt() and 0xFF) - (a[i].toInt() and 0xFF)) +
                abs((e[i + 1].toInt() and 0xFF) - (a[i + 1].toInt() and 0xFF)) +
                abs((e[i + 2].toInt() and 0xFF) - (a[i + 2].toInt() and 0xFF))
            val tile = tileRow + x / PixelComparison.TILE_SIZE
            tileSums[tile] += difference.toLong()
            tileSamples[tile] += 3
            totalSum += difference
        }
    }

    var worst = 0.0
    var worstIndex = 0
    var differing = 0
    for (tile in tileSums.indices) {
        val mean = tileSums[tile].toDouble() / tileSamples[tile]
        if (mean > PixelComparison.MAX_TILE_MEAN_DIFFERENCE) differing++
        if (mean > worst) {
            worst = mean
            worstIndex = tile
        }
    }
    val worstX = (worstIndex % tilesX) * PixelComparison.TILE_SIZE
    val worstY = (worstIndex / tilesX) * PixelComparison.TILE_SIZE

    return PixelComparison(
        sizeMatches = true,
        expectedSize = expectedSize,
        actualSize = actualSize,
        meanAbsoluteDifference = totalSum.toDouble() / (width.toLong() * height * 3),
        worstTileMeanDifference = worst,
        worstTile = "at ($worstX, $worstY)",
        differingTiles = differing
    )
}

fun assertPixelsMatch(expected: RgbaImage, actual: RgbaImage, what: String) {
    val comparison = comparePixels(expected, actual)
    assertTrue(comparison.matches, "$what: expected the PDF page to match the reference render, but $comparison")
}

fun assertPixelsDiffer(expected: RgbaImage, actual: RgbaImage, what: String) {
    val comparison = comparePixels(expected, actual)
    assertFalse(
        comparison.matches,
        "$what: negative control matched when it shouldn't, so the comparison is too weak ($comparison)"
    )
}
