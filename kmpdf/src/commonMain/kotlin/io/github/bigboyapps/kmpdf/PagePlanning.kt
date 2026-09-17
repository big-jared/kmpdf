package io.github.bigboyapps.kmpdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.max

/** The largest page a PDF can have: 200 inches (14,400 points) in either dimension. */
internal const val MAX_PDF_PAGE_SIZE_PT = 14_400f

/** Pages are rendered at 2 pixels per point for sharper output. */
internal const val PAGE_RENDER_SCALE = 2f

private val MAX_PAGE_PT = MAX_PDF_PAGE_SIZE_PT.toInt()

/**
 * A page with its final size and position in the document, ready to render. [content] already applies
 * the margins and provides [LocalPdfPageInfo].
 */
internal class PlannedPage(
    val widthPt: Float,
    val heightPt: Float,
    val info: PdfPageInfo,
    val content: @Composable () -> Unit
)

/** The configuration can't produce a valid page, for example margins that leave no room for content. */
internal class PdfConfigException(message: String) : Exception(message)

/** Content is too tall to place: a wrap-height page over the PDF limit, or an item taller than a page. */
internal class PdfPageTooTallException(message: String) : Exception(message)

/** Measures composables at a width in pixels (at [PAGE_RENDER_SCALE]) and returns each one's height in pixels. */
internal typealias ContentHeightMeasurer = suspend (contents: List<@Composable () -> Unit>, widthPx: Int) -> List<Int>

/** A page whose size is known but whose page number isn't yet. */
private class PageDraft(
    val widthPt: Float,
    val heightPt: Float,
    val content: (PdfPageInfo) -> @Composable () -> Unit
)

/**
 * Resolves every requested page to its final size, splits flowing items into pages, and numbers the pages.
 *
 * @throws PdfConfigException when a page size, the margins, or a header and footer leave no valid layout.
 * @throws PdfPageTooTallException when content can't fit on a page.
 */
internal suspend fun planPages(
    config: PdfConfig,
    specs: List<PageSpec>,
    measureContentHeightsPx: ContentHeightMeasurer
): List<PlannedPage> {
    val drafts = mutableListOf<PageDraft>()
    for (spec in specs) {
        when (spec) {
            is PageSpec.Single -> drafts += planSingle(config, spec, pageNumber = drafts.size + 1, measureContentHeightsPx)
            is PageSpec.Flow -> drafts += planFlow(config, spec, measureContentHeightsPx)
        }
    }
    return drafts.mapIndexed { index, draft ->
        val info = PdfPageInfo(pageNumber = index + 1, pageCount = drafts.size)
        PlannedPage(draft.widthPt, draft.heightPt, info, draft.content(info))
    }
}

private suspend fun planSingle(
    config: PdfConfig,
    spec: PageSpec.Single,
    pageNumber: Int,
    measureContentHeightsPx: ContentHeightMeasurer
): PageDraft {
    val size = spec.size ?: config.pageSize
    val margins = config.margins
    val widthPt = validatedWidth(size, "Page $pageNumber")
    val content = { info: PdfPageInfo -> pageContent(info, margins, spec.content) }

    if (!size.height.value.isNaN()) {
        val heightPt = validatedHeight(size, "Page $pageNumber")
        margins.contentAreaError(widthPt, heightPt)?.let { throw PdfConfigException("Page $pageNumber: $it") }
        return PageDraft(widthPt, heightPt, content)
    }

    val contentWidthPt = widthPt - margins.left.value - margins.right.value
    if (contentWidthPt <= 0f) {
        throw PdfConfigException(
            "Page $pageNumber: Margins (left ${margins.left.value}, right ${margins.right.value} pt) " +
                "leave no room for content on a $widthPt pt wide page"
        )
    }
    val sampleInfo = PdfPageInfo(pageNumber = 1, pageCount = 1)
    val contentHeightPx = measureContentHeightsPx(
        listOf { CompositionLocalProvider(LocalPdfPageInfo provides sampleInfo) { spec.content() } },
        (contentWidthPt * PAGE_RENDER_SCALE).toInt().coerceAtLeast(1)
    ).single()
    val heightPt = max(1f, ceil(contentHeightPx / PAGE_RENDER_SCALE + margins.top.value + margins.bottom.value))
    if (heightPt > MAX_PDF_PAGE_SIZE_PT) {
        throw PdfPageTooTallException("Page $pageNumber content is $heightPt pt tall, over the $MAX_PAGE_PT pt PDF page limit")
    }
    return PageDraft(widthPt, heightPt, content)
}

private suspend fun planFlow(
    config: PdfConfig,
    spec: PageSpec.Flow,
    measureContentHeightsPx: ContentHeightMeasurer
): List<PageDraft> {
    if (spec.items.isEmpty()) return emptyList()

    val size = config.pageSize
    val margins = config.margins
    val widthPt = validatedWidth(size, "pages(...)")
    if (size.height.value.isNaN()) {
        throw PdfConfigException("pages(...) needs a fixed page height, but PdfConfig.pageSize uses wrapHeight")
    }
    val heightPt = validatedHeight(size, "pages(...)")
    margins.contentAreaError(widthPt, heightPt)?.let { throw PdfConfigException("pages(...): $it") }
    val contentWidthPt = widthPt - margins.left.value - margins.right.value
    val contentHeightPt = heightPt - margins.top.value - margins.bottom.value

    // Measure the header, footer, and every item in one pass
    val sampleInfo = PdfPageInfo(pageNumber = 1, pageCount = 1)
    val header = spec.header
    val footer = spec.footer
    val decorations = listOfNotNull(
        header?.let { h -> @Composable { CompositionLocalProvider(LocalPdfPageInfo provides sampleInfo) { h(sampleInfo) } } },
        footer?.let { f -> @Composable { CompositionLocalProvider(LocalPdfPageInfo provides sampleInfo) { f(sampleInfo) } } }
    )
    val heightsPx = measureContentHeightsPx(
        decorations + spec.items,
        (contentWidthPt * PAGE_RENDER_SCALE).toInt().coerceAtLeast(1)
    )
    var next = 0
    val headerHeightPt = if (header != null) heightsPx[next++] / PAGE_RENDER_SCALE else 0f
    val footerHeightPt = if (footer != null) heightsPx[next++] / PAGE_RENDER_SCALE else 0f
    val itemHeightsPt = heightsPx.drop(next).map { it / PAGE_RENDER_SCALE }

    val availablePt = contentHeightPt - headerHeightPt - footerHeightPt
    if (availablePt <= 0f) {
        throw PdfConfigException(
            "pages(...): the header ($headerHeightPt pt) and footer ($footerHeightPt pt) leave no room for items " +
                "in $contentHeightPt pt of page content"
        )
    }

    val packing = packIntoPages(itemHeightsPt, availablePt, spec.itemSpacing.value)
    val pageRanges = when (packing) {
        is PagePacking.Packed -> packing.pages
        is PagePacking.ItemTooTall -> throw PdfPageTooTallException(
            "Item ${packing.index + 1} in pages(...) is ${packing.itemHeight} pt tall, but only " +
                "${packing.availableHeight} pt fits on a page"
        )
    }

    return pageRanges.map { range ->
        val pageItems = spec.items.slice(range)
        PageDraft(widthPt, heightPt) { info ->
            pageContent(info, margins) { FlowPage(header, footer, pageItems, spec.itemSpacing, info) }
        }
    }
}

private fun validatedWidth(size: PageSize, what: String): Float {
    val widthPt = size.width.value
    if (!(widthPt > 0f && widthPt <= MAX_PDF_PAGE_SIZE_PT)) {
        throw PdfConfigException("$what width must be more than 0 and at most $MAX_PAGE_PT pt, got $widthPt")
    }
    return widthPt
}

private fun validatedHeight(size: PageSize, what: String): Float {
    val heightPt = size.height.value
    if (!(heightPt > 0f && heightPt <= MAX_PDF_PAGE_SIZE_PT)) {
        throw PdfConfigException("$what height must be more than 0 and at most $MAX_PAGE_PT pt, got $heightPt")
    }
    return heightPt
}

private fun pageContent(
    info: PdfPageInfo,
    margins: PdfMargins,
    content: @Composable () -> Unit
): @Composable () -> Unit = {
    CompositionLocalProvider(LocalPdfPageInfo provides info) {
        PageRoot(content, margins)
    }
}

/** One page of flowing items: the header at the top, the items below it, and the footer at the bottom. */
@Composable
private fun FlowPage(
    header: (@Composable (PdfPageInfo) -> Unit)?,
    footer: (@Composable (PdfPageInfo) -> Unit)?,
    items: List<@Composable () -> Unit>,
    itemSpacing: Dp,
    info: PdfPageInfo
) {
    Column(Modifier.fillMaxSize()) {
        // Each part is wrapped in a Box, the same way it was measured
        if (header != null) Box { header(info) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
            items.forEach { item -> Box { item() } }
        }
        if (footer != null) Box { footer(info) }
    }
}

/**
 * Reports how tall each of [contents] wants to be at the available width with no height limit, laid out
 * the same way page content is.
 */
@Composable
internal fun MeasureContentHeights(contents: List<@Composable () -> Unit>, onMeasured: (List<Int>) -> Unit) {
    Layout(content = { contents.forEach { content -> Box { content() } } }) { measurables, constraints ->
        val childConstraints = Constraints(maxWidth = constraints.maxWidth, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(childConstraints) }
        onMeasured(placeables.map { it.height })
        layout(constraints.maxWidth, constraints.minHeight) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}
