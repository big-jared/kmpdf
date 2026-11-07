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
import kotlin.math.roundToInt

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

/** Pages planned from one [PageSpec]. */
private sealed interface PlannedPart {
    val pageCount: Int
    fun drafts(): List<PageDraft>
}

private class FixedPage(private val draft: PageDraft) : PlannedPart {
    override val pageCount = 1
    override fun drafts() = listOf(draft)
}

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
    val parts = mutableListOf<PlannedPart>()
    for (spec in specs) {
        parts += when (spec) {
            is PageSpec.Single -> {
                val pageNumber = parts.sumOf { it.pageCount } + 1
                FixedPage(planSingle(config, spec, pageNumber, measureContentHeightsPx))
            }
            is PageSpec.Flow -> planFlow(config, spec, measureContentHeightsPx) ?: continue
        }
    }
    reserveRoomForDecorations(parts, measureContentHeightsPx)

    val drafts = parts.flatMap { it.drafts() }
    return drafts.mapIndexed { index, draft ->
        val info = PdfPageInfo(pageNumber = index + 1, pageCount = drafts.size)
        PlannedPage(draft.widthPt, draft.heightPt, info, draft.content(info))
    }
}

/**
 * Headers and footers can change with the page, like a "Continued" line from page 2 on or a page count that
 * wraps, so each page of flowing items reserves room for its own header and footer. Which page an item lands on
 * depends on that room, and the room on the page numbers, so this repeats until no flow's page count changes.
 * Every page's header and footer were then measured with the page number and count it's rendered with.
 */
private suspend fun reserveRoomForDecorations(parts: List<PlannedPart>, measureContentHeightsPx: ContentHeightMeasurer) {
    if (parts.none { it is FlowPlan && it.hasDecorations }) return
    do {
        val pageCount = parts.sumOf { it.pageCount }
        var firstPageNumber = 1
        var changed = false
        for (part in parts) {
            val partPageCount = part.pageCount
            if (part is FlowPlan && part.hasDecorations) {
                val infos = List(partPageCount) { PdfPageInfo(pageNumber = firstPageNumber + it, pageCount = pageCount) }
                if (part.reserveRoomFor(infos, measureContentHeightsPx)) changed = true
            }
            firstPageNumber += partPageCount
        }
    } while (changed)
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
        contentWidthPx(widthPt, margins)
    ).single()
    // Margins are rounded to whole pixels when laid out, so size the page from the rounded margins
    val pageHeightPx = contentHeightPx + marginPx(margins.top) + marginPx(margins.bottom)
    val heightPt = max(1f, ceil(pageHeightPx / PAGE_RENDER_SCALE))
    if (heightPt > MAX_PDF_PAGE_SIZE_PT) {
        throw PdfPageTooTallException("Page $pageNumber content is $heightPt pt tall, over the $MAX_PAGE_PT pt PDF page limit")
    }
    return PageDraft(widthPt, heightPt, content)
}

private suspend fun planFlow(
    config: PdfConfig,
    spec: PageSpec.Flow,
    measureContentHeightsPx: ContentHeightMeasurer
): FlowPlan? {
    if (spec.items.isEmpty()) return null

    val size = config.pageSize
    val margins = config.margins
    val widthPt = validatedWidth(size, "pages(...)")
    if (size.height.value.isNaN()) {
        throw PdfConfigException("pages(...) needs a fixed page height, but PdfConfig.pageSize uses wrapHeight")
    }
    val heightPt = validatedHeight(size, "pages(...)")
    margins.contentAreaError(widthPt, heightPt)?.let { throw PdfConfigException("pages(...): $it") }

    // Measure every item, and the header and footer of a first page, in one pass
    val sampleInfo = PdfPageInfo(pageNumber = 1, pageCount = 1)
    val decorations = decorationsFor(spec, sampleInfo)
    val contentWidthPx = contentWidthPx(widthPt, margins)
    val heightsPx = measureContentHeightsPx(decorations + spec.items, contentWidthPx)

    return FlowPlan(
        spec = spec,
        widthPt = widthPt,
        heightPt = heightPt,
        margins = margins,
        contentWidthPx = contentWidthPx,
        itemHeightsPx = heightsPx.drop(decorations.size),
        firstPageDecorationsPx = heightsPx.take(decorations.size).sum()
    )
}

/** The header and footer of a page of [spec] with [info], in the order they're measured. */
private fun decorationsFor(spec: PageSpec.Flow, info: PdfPageInfo): List<@Composable () -> Unit> = listOfNotNull(
    spec.header?.let { header -> @Composable { CompositionLocalProvider(LocalPdfPageInfo provides info) { header(info) } } },
    spec.footer?.let { footer -> @Composable { CompositionLocalProvider(LocalPdfPageInfo provides info) { footer(info) } } }
)

/**
 * Items of a [PageSpec.Flow] split into pages. Heights are in the pixels pages are rendered at, so page breaks
 * match the rendered layout exactly.
 */
private class FlowPlan(
    private val spec: PageSpec.Flow,
    private val widthPt: Float,
    private val heightPt: Float,
    private val margins: PdfMargins,
    private val contentWidthPx: Int,
    private val itemHeightsPx: List<Int>,
    firstPageDecorationsPx: Int
) : PlannedPart {
    private val contentHeightPx = contentHeightPx(heightPt, margins)

    // Arrangement.spacedBy rounds the spacing to whole pixels, so plan with the rounded spacing
    private val spacingPx = (spec.itemSpacing.value * PAGE_RENDER_SCALE).roundToInt()

    /**
     * Room for the header and footer on each measured page, by page index, and on pages not measured yet.
     * The largest measurement so far is kept, so page counts settle instead of going back and forth.
     */
    private val reservedPx = mutableListOf<Int>()
    private var unmeasuredReservedPx = firstPageDecorationsPx

    private var pageRanges = pack()

    val hasDecorations: Boolean get() = spec.header != null || spec.footer != null

    override val pageCount: Int get() = pageRanges.size

    /** Measures the header and footer of pages with [infos], reserves room for them, and returns whether the page count changed. */
    suspend fun reserveRoomFor(infos: List<PdfPageInfo>, measureContentHeightsPx: ContentHeightMeasurer): Boolean {
        val decorationsPerPage = listOfNotNull(spec.header, spec.footer).size
        val heightsPx = measureContentHeightsPx(infos.flatMap { decorationsFor(spec, it) }, contentWidthPx)
        heightsPx.chunked(decorationsPerPage).forEachIndexed { index, pageHeightsPx ->
            val px = pageHeightsPx.sum()
            if (index < reservedPx.size) reservedPx[index] = max(reservedPx[index], px) else reservedPx += px
            unmeasuredReservedPx = max(unmeasuredReservedPx, px)
        }
        val before = pageCount
        pageRanges = pack()
        return pageCount != before
    }

    override fun drafts(): List<PageDraft> = pageRanges.map { range ->
        val pageItems = spec.items.slice(range)
        PageDraft(widthPt, heightPt) { info ->
            pageContent(info, margins) { FlowPage(spec.header, spec.footer, pageItems, spec.itemSpacing, info) }
        }
    }

    private fun pack(): List<IntRange> {
        val packing = packIntoPages(
            itemHeights = itemHeightsPx.map { it.toFloat() },
            availableHeight = { pageIndex -> availablePx(pageIndex).toFloat() },
            spacing = spacingPx.toFloat()
        )
        return when (packing) {
            is PagePacking.Packed -> packing.pages
            is PagePacking.ItemTooTall -> throw PdfPageTooTallException(
                "Item ${packing.index + 1} in pages(...) is ${packing.itemHeight / PAGE_RENDER_SCALE} pt tall, but only " +
                    "${packing.availableHeight / PAGE_RENDER_SCALE} pt fits on a page"
            )
        }
    }

    private fun availablePx(pageIndex: Int): Int {
        val reserved = reservedPx.getOrNull(pageIndex) ?: unmeasuredReservedPx
        val available = contentHeightPx - reserved
        if (available <= 0) {
            throw PdfConfigException(
                "pages(...): the header and footer (${reserved / PAGE_RENDER_SCALE} pt) on page ${pageIndex + 1} " +
                    "leave no room for items in ${contentHeightPx / PAGE_RENDER_SCALE} pt of page content"
            )
        }
        return available
    }
}

/** The size of a page dimension in rendered pixels. */
internal fun pagePx(pt: Float): Int = (pt * PAGE_RENDER_SCALE).toInt()

/** A margin in rendered pixels, rounded the way padding rounds it. */
private fun marginPx(margin: Dp): Int = (margin.value * PAGE_RENDER_SCALE).roundToInt()

/** The width page content is laid out at: the page in whole pixels, less the margins as padding rounds them. */
private fun contentWidthPx(widthPt: Float, margins: PdfMargins): Int =
    (pagePx(widthPt) - marginPx(margins.left) - marginPx(margins.right)).coerceAtLeast(1)

/** The height page content is laid out in: the page in whole pixels, less the margins as padding rounds them. */
private fun contentHeightPx(heightPt: Float, margins: PdfMargins): Int =
    pagePx(heightPt) - marginPx(margins.top) - marginPx(margins.bottom)

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
