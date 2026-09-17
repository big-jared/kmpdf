package io.github.bigboyapps.kmpdf

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.max

/** The largest page a PDF can have: 200 inches (14,400 points) in either dimension. */
internal const val MAX_PDF_PAGE_SIZE_PT = 14_400f

/** Pages are rendered at 2 pixels per point for sharper output. */
internal const val PAGE_RENDER_SCALE = 2f

/** A page with its final size, ready to render. [content] already applies the margins. */
internal class PlannedPage(val widthPt: Float, val heightPt: Float, val content: @Composable () -> Unit)

/** The configuration can't produce a valid page, for example margins that leave no room for content. */
internal class PdfConfigException(message: String) : Exception(message)

/** A wrap-height page's content is taller than a PDF page can be. */
internal class PdfPageTooTallException(message: String) : Exception(message)

/**
 * Resolves every requested page to its final size.
 *
 * Fixed-size pages are validated. Wrap-height pages are measured at their content width with no height
 * limit, then sized to the content plus the vertical margins, rounded up to whole points.
 *
 * @param measureContentHeightPx Measures content at a width in pixels (at [PAGE_RENDER_SCALE]) and returns
 *                               its height in pixels.
 * @throws PdfConfigException when a page size or the margins are invalid.
 * @throws PdfPageTooTallException when wrap-height content exceeds [MAX_PDF_PAGE_SIZE_PT].
 */
internal suspend fun planPages(
    config: PdfConfig,
    specs: List<PageSpec>,
    measureContentHeightPx: suspend (content: @Composable () -> Unit, widthPx: Int) -> Int
): List<PlannedPage> = specs.mapIndexed { index, spec ->
    val pageNumber = index + 1
    val size = spec.size ?: config.pageSize
    val margins = config.margins
    val content: @Composable () -> Unit = { PageRoot(spec.content, margins) }

    val widthPt = size.width.value
    if (!(widthPt > 0f && widthPt <= MAX_PDF_PAGE_SIZE_PT)) {
        throw PdfConfigException("Page $pageNumber width must be more than 0 and at most $MAX_PAGE_PT pt, got $widthPt")
    }

    if (!size.height.value.isNaN()) {
        val heightPt = size.height.value
        if (!(heightPt > 0f && heightPt <= MAX_PDF_PAGE_SIZE_PT)) {
            throw PdfConfigException("Page $pageNumber height must be more than 0 and at most $MAX_PAGE_PT pt, got $heightPt")
        }
        margins.contentAreaError(widthPt, heightPt)?.let { throw PdfConfigException("Page $pageNumber: $it") }
        PlannedPage(widthPt, heightPt, content)
    } else {
        val contentWidthPt = widthPt - margins.left.value - margins.right.value
        if (contentWidthPt <= 0f) {
            throw PdfConfigException(
                "Page $pageNumber: Margins (left ${margins.left.value}, right ${margins.right.value} pt) " +
                    "leave no room for content on a $widthPt pt wide page"
            )
        }
        val contentWidthPx = max(1, (contentWidthPt * PAGE_RENDER_SCALE).toInt())
        val contentHeightPx = measureContentHeightPx(spec.content, contentWidthPx)
        val heightPt = max(1f, ceil(contentHeightPx / PAGE_RENDER_SCALE + margins.top.value + margins.bottom.value))
        if (heightPt > MAX_PDF_PAGE_SIZE_PT) {
            throw PdfPageTooTallException(
                "Page $pageNumber content is $heightPt pt tall, over the $MAX_PAGE_PT pt PDF page limit"
            )
        }
        PlannedPage(widthPt, heightPt, content)
    }
}

private val MAX_PAGE_PT = MAX_PDF_PAGE_SIZE_PT.toInt()

/**
 * Reports how tall [content] wants to be at the available width with no height limit, laid out the same
 * way [PageRoot] lays out page content.
 */
@Composable
internal fun MeasureContentHeight(content: @Composable () -> Unit, onMeasured: (Int) -> Unit) {
    Layout(content = { Box { content() } }) { measurables, constraints ->
        val placeable = measurables.single().measure(
            Constraints(maxWidth = constraints.maxWidth, maxHeight = Constraints.Infinity)
        )
        onMeasured(placeable.height)
        layout(constraints.maxWidth, constraints.minHeight) {
            placeable.place(0, 0)
        }
    }
}
