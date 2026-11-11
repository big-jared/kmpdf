package io.github.bigboyapps.kmpdf

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests page planning with a fake measurer, so page breaks and numbering are checked without rendering. */
class PagePlanningTest {
    private val letter = PdfConfig(pageSize = PageSize.Letter, margins = PdfMargins.all(36.dp))
    private val nothing: @Composable () -> Unit = { Box {} }

    /**
     * A measurer that returns the first call's heights, in points, from [first], and the heights of headers and
     * footers measured afterwards from [decoration], given each one's position in its call.
     */
    private fun fakeMeasurer(
        first: List<Float> = emptyList(),
        decoration: (index: Int) -> Float = { 0f }
    ): Pair<ContentHeightMeasurer, List<Int>> {
        val requestedCounts = mutableListOf<Int>()
        val measurer: ContentHeightMeasurer = { contents, _ ->
            val heights = if (requestedCounts.isEmpty()) first else List(contents.size, decoration)
            check(contents.size == heights.size) { "Measured ${contents.size} contents, expected ${heights.size}" }
            requestedCounts += contents.size
            heights.map { (it * PAGE_RENDER_SCALE).toInt() }
        }
        return measurer to requestedCounts
    }

    private fun flow(count: Int, header: Boolean = false, footer: Boolean = false, spacing: Float = 0f) = PageSpec.Flow(
        items = List(count) { nothing },
        itemSpacing = spacing.dp,
        header = if (header) { _ -> } else null,
        footer = if (footer) { _ -> } else null
    )

    @Test
    fun headerAndFooterReduceTheSpaceForItems() = runTest {
        // Letter with 36 pt margins leaves 720 pt; a 100 pt header and 20 pt footer leave 600 pt for items
        val (measurer, requested) = fakeMeasurer(listOf(100f, 20f) + List(7) { 200f }) { if (it % 2 == 0) 100f else 20f }
        val pages = planPages(letter, listOf(flow(7, header = true, footer = true)), measurer)

        assertEquals(9, requested.first(), "Header, footer, and items should be measured in one pass")
        assertEquals(listOf(9, 6), requested, "Then each page's header and footer should be measured once")
        assertEquals(3, pages.size, "Three 200 pt items fit in 600 pt, so 7 items need 3 pages")
        pages.forEach { page ->
            assertEquals(612f, page.widthPt)
            assertEquals(792f, page.heightPt)
        }
    }

    @Test
    fun pagesAreNumberedAcrossTheWholeDocument() = runTest {
        val (measurer, _) = fakeMeasurer(List(5) { 400f })
        val specs = listOf(
            PageSpec.Single(size = null, content = nothing),
            flow(5, spacing = 10f),
            PageSpec.Single(size = PageSize.A4, content = nothing)
        )
        val pages = planPages(letter, specs, measurer)

        // 400 + 10 + 400 > 720, so each item gets its own page: 1 + 5 + 1 pages
        assertEquals((1..7).map { PdfPageInfo(it, 7) }, pages.map { it.info })
        assertEquals(595f, pages.last().widthPt)
    }

    @Test
    fun emptyItemListAddsNoPages() = runTest {
        val (measurer, requested) = fakeMeasurer()
        val pages = planPages(letter, listOf(flow(0), PageSpec.Single(null, nothing)), measurer)

        assertEquals(listOf(PdfPageInfo(1, 1)), pages.map { it.info })
        assertTrue(requested.isEmpty(), "Nothing should be measured for an empty item list")
    }

    @Test
    fun itemTallerThanTheAvailableSpaceIsReported() = runTest {
        val (measurer, _) = fakeMeasurer(listOf(50f, 100f, 700f, 10f))
        val error = assertFailsWith<PdfPageTooTallException> {
            planPages(letter, listOf(flow(3, header = true)), measurer)
        }

        assertTrue(error.message!!.contains("Item 2"), "Should name the item: ${error.message}")
    }

    @Test
    fun headerAndFooterWithNoRoomForItemsAreRejected() = runTest {
        val (measurer, _) = fakeMeasurer(listOf(400f, 400f, 10f))
        assertFailsWith<PdfConfigException> {
            planPages(letter, listOf(flow(1, header = true, footer = true)), measurer)
        }
    }

    @Test
    fun flowingItemsNeedAFixedPageHeight() = runTest {
        val (measurer, _) = fakeMeasurer()
        val error = assertFailsWith<PdfConfigException> {
            planPages(PdfConfig(pageSize = PageSize.wrapHeight(400.dp)), listOf(flow(2)), measurer)
        }

        assertTrue(error.message!!.contains("wrapHeight"), "Should explain the page height problem: ${error.message}")
    }

    @Test
    fun wrapHeightPageUsesTheMeasuredHeight() = runTest {
        val (measurer, _) = fakeMeasurer(listOf(100.5f))
        val page = planPages(letter, listOf(PageSpec.Single(PageSize.wrapHeight(300.dp), nothing)), measurer).single()

        // 100.5 pt of content + 72 pt of margins = 172.5, rounded up
        assertEquals(173f, page.heightPt)
    }

    @Test
    fun headerThatGrowsAfterTheFirstPageGetsItsOwnRoom() = runTest {
        // A 50 pt header on page 1 and a 150 pt one after it leave 670 pt, then 570 pt, for 100 pt items.
        // Planning every page with page 1's header would put 6 items on each of 2 pages.
        val (measurer, _) = fakeMeasurer(listOf(50f) + List(12) { 100f }) { if (it == 0) 50f else 150f }
        val pages = planPages(letter, listOf(flow(12, header = true)), measurer)

        assertEquals((1..3).map { PdfPageInfo(it, 3) }, pages.map { it.info }, "6, 5, and 1 items")
    }

    @Test
    fun spacingIsPlannedAsWholePixels() = runTest {
        // 5 × 140.5 pt + 4 × 4.3 pt = 719.7 pt fits in 720 pt, but the spacing is drawn as 4.5 pt, which doesn't
        val (measurer, _) = fakeMeasurer(List(5) { 140.5f })
        val pages = planPages(letter, listOf(flow(5, spacing = 4.3f)), measurer)

        assertEquals(2, pages.size)
    }

    @Test
    fun wrapHeightPageFitsContentAfterMarginsAreRounded() = runTest {
        // 0.25 pt margins are laid out as 1 px each, so 101 px of content needs a 103 px (51.5 pt) page
        val config = PdfConfig(margins = PdfMargins(top = 0.25.dp, bottom = 0.25.dp))
        val (measurer, _) = fakeMeasurer(listOf(50.5f))
        val page = planPages(config, listOf(PageSpec.Single(PageSize.wrapHeight(300.dp), nothing)), measurer).single()

        assertEquals(52f, page.heightPt)
    }
}
