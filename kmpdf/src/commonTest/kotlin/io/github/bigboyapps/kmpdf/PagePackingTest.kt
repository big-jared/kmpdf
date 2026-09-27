package io.github.bigboyapps.kmpdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PagePackingTest {
    private fun pages(heights: List<Float>, available: Float, spacing: Float = 0f): List<IntRange> {
        val result = packIntoPages(heights, available, spacing)
        check(result is PagePacking.Packed) { "Expected items to fit, got $result" }
        return result.pages
    }

    @Test
    fun noItemsMeansNoPages() {
        assertEquals(emptyList(), pages(emptyList(), available = 100f))
    }

    @Test
    fun itemsThatFitExactlyShareAPage() {
        assertEquals(listOf(0..1), pages(listOf(40f, 60f), available = 100f))
    }

    @Test
    fun floatingPointExactFitStaysOnOnePage() {
        assertEquals(listOf(0..2), pages(listOf(33.333333f, 33.333333f, 33.333334f), available = 100f))
    }

    @Test
    fun overflowStartsANewPageWithoutSplittingItems() {
        assertEquals(listOf(0..1, 2..2), pages(listOf(40f, 50f, 20f), available = 100f))
    }

    @Test
    fun spacingOnlyCountsBetweenItemsOnTheSamePage() {
        // 30 + 10 + 30 + 10 + 30 = 110 doesn't fit in 100, but 30 + 10 + 30 does
        assertEquals(listOf(0..1, 2..3), pages(listOf(30f, 30f, 30f, 30f), available = 100f, spacing = 10f))
        // Spacing isn't added after the last item: 45 + 10 + 45 = 100 fits exactly
        assertEquals(listOf(0..1), pages(listOf(45f, 45f), available = 100f, spacing = 10f))
    }

    @Test
    fun itemThatFillsAWholePageGetsItsOwnPage() {
        assertEquals(listOf(0..0, 1..1, 2..2), pages(listOf(10f, 100f, 10f), available = 100f))
    }

    @Test
    fun zeroHeightItemsArePlacedWithoutUsingSpace() {
        assertEquals(listOf(0..3), pages(listOf(0f, 100f, 0f, 0f), available = 100f))
    }

    @Test
    fun variedHeightsFillPagesGreedilyInOrder() {
        val heights = List(100) { index -> 20f + (index * 37 % 90) }
        val packed = pages(heights, available = 400f, spacing = 8f)

        assertEquals((0 until 100).toList(), packed.flatMap { it.toList() }, "Every item appears once, in order")
        packed.forEach { page ->
            val used = page.sumOf { heights[it].toDouble() } + 8.0 * (page.count() - 1)
            assertTrue(used <= 400.01, "Page $page overflows: $used")
        }
        packed.zipWithNext().forEach { (page, next) ->
            val usedWithNext = page.sumOf { heights[it].toDouble() } + 8.0 * page.count() + heights[next.first]
            assertTrue(usedWithNext > 400.0, "Item ${next.first} would have fit on page $page")
        }
    }

    @Test
    fun itemTallerThanAPageIsReported() {
        val result = packIntoPages(listOf(10f, 150f, 10f), availableHeight = 100f)

        assertEquals(PagePacking.ItemTooTall(index = 1, itemHeight = 150f, availableHeight = 100f), result)
    }

    @Test
    fun rejectsInvalidInput() {
        assertFailsWith<IllegalArgumentException> { packIntoPages(listOf(10f), availableHeight = 0f) }
        assertFailsWith<IllegalArgumentException> { packIntoPages(listOf(10f), availableHeight = 100f, spacing = -1f) }
        assertFailsWith<IllegalArgumentException> { packIntoPages(listOf(-1f), availableHeight = 100f) }
    }
}
