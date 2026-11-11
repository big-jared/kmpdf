package io.github.bigboyapps.kmpdf

/** Floating point slack so content that fits exactly (e.g. 3 × 33.333 in 100) isn't pushed to a new page. */
private const val FIT_EPSILON = 0.01f

/** The result of splitting items into pages. */
internal sealed class PagePacking {
    /** Each page holds a consecutive range of item indices, in order. */
    data class Packed(val pages: List<IntRange>) : PagePacking()

    /** The item at [index] is taller than a whole page's available height, so it can't be placed. */
    data class ItemTooTall(val index: Int, val itemHeight: Float, val availableHeight: Float) : PagePacking()
}

/**
 * Splits items into pages in order, never splitting an item across pages.
 *
 * @param itemHeights The measured height of each item.
 * @param availableHeight The height available for items on each page, in the same unit.
 * @param spacing The gap between consecutive items on the same page. Not added before the first or after the last item.
 */
internal fun packIntoPages(itemHeights: List<Float>, availableHeight: Float, spacing: Float = 0f): PagePacking {
    require(availableHeight > 0f) { "Available page height must be positive, was $availableHeight" }
    return packIntoPages(itemHeights, { availableHeight }, spacing)
}

/**
 * Splits items into pages in order, never splitting an item across pages, where each page can have a
 * different height available for items.
 *
 * @param availableHeight The height available for items on the page at a zero-based index.
 */
internal fun packIntoPages(
    itemHeights: List<Float>,
    availableHeight: (pageIndex: Int) -> Float,
    spacing: Float = 0f
): PagePacking {
    require(spacing >= 0f) { "Item spacing can't be negative, was $spacing" }

    val pages = mutableListOf<IntRange>()
    var pageStart = 0
    var usedHeight = 0f

    itemHeights.forEachIndexed { index, height ->
        require(height >= 0f) { "Item $index has a negative height ($height)" }
        if (index != pageStart) {
            val heightWithSpacing = usedHeight + spacing + height
            if (heightWithSpacing <= availableHeight(pages.size) + FIT_EPSILON) {
                usedHeight = heightWithSpacing
                return@forEachIndexed
            }
            pages += pageStart until index
            pageStart = index
        }
        val available = availableHeight(pages.size)
        if (height > available + FIT_EPSILON) {
            return PagePacking.ItemTooTall(index, height, available)
        }
        usedHeight = height
    }

    if (pageStart < itemHeights.size) {
        pages += pageStart until itemHeights.size
    }
    return PagePacking.Packed(pages)
}
