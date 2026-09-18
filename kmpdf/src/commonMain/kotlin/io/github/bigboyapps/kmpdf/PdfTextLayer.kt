package io.github.bigboyapps.kmpdf

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer

/**
 * One line of text on a page, in points from the page's top-left corner. Written as invisible text over the
 * page image so the text can be selected, searched, and copied.
 *
 * @property baseline The line's baseline, measured from the top of the page.
 */
internal data class PageTextLine(
    val text: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val baseline: Float
)

/**
 * Collects every visible line of text on a rendered page from Compose's semantics tree.
 *
 * Every `BasicText` (and so Material `Text`) exposes its text layout through semantics. Lines that are
 * clipped away, or outside the page, are left out. Text is collected in logical order, so right-to-left
 * text reads correctly when copied but some viewers show it reversed when extracting it.
 *
 * @param pageWidthPx The page width in pixels, at [PAGE_RENDER_SCALE].
 * @param pageHeightPx The page height in pixels, at [PAGE_RENDER_SCALE].
 */
internal fun SemanticsOwner.pageTextLines(pageWidthPx: Int, pageHeightPx: Int): List<PageTextLine> {
    val lines = mutableListOf<PageTextLine>()
    for (node in getAllSemanticsNodes(mergingEnabled = false)) {
        val getTextLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: continue
        val layouts = mutableListOf<TextLayoutResult>()
        if (getTextLayout(layouts) != true) continue
        // The text is drawn by the innermost modifier, BasicText's own layout modifier. The semantics node's
        // position is where its outermost semantics modifier is, which is outside any padding in between.
        val textCoordinates = node.layoutInfo.getModifierInfo().lastOrNull()?.coordinates?.takeIf { it.isAttached }
        val origin = textCoordinates?.positionInRoot() ?: node.positionInRoot
        // Bounds in the root are clipped by ancestors, so text scrolled or clipped out of view is excluded
        val visible = textCoordinates?.boundsInRoot() ?: node.boundsInRoot
        val layout = layouts.firstOrNull()?.remeasured(textCoordinates?.size?.width) ?: continue
        val text = layout.layoutInput.text.text

        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line, visibleEnd = true)
            // Leave out leading and trailing whitespace, which is drawn as blank space
            val textStart = (start until end).firstOrNull { !text[it].isWhitespace() } ?: continue
            val textEnd = (start until end).last { !text[it].isWhitespace() } + 1
            val lineText = text.substring(textStart, textEnd)

            // The line's extent is the extent of its characters, which works in either direction
            var lineLeft = Float.MAX_VALUE
            var lineRight = -Float.MAX_VALUE
            for (offset in textStart until textEnd) {
                val box = layout.getBoundingBox(offset)
                if (box.width <= 0f) continue
                lineLeft = minOf(lineLeft, box.left)
                lineRight = maxOf(lineRight, box.right)
            }
            if (lineRight <= lineLeft) continue

            val left = origin.x + lineLeft
            val right = origin.x + lineRight
            val top = origin.y + layout.getLineTop(line)
            val bottom = origin.y + layout.getLineBottom(line)
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2

            val isVisible = centerY >= visible.top && centerY <= visible.bottom &&
                centerX >= visible.left && centerX <= visible.right &&
                centerY >= 0f && centerY <= pageHeightPx && centerX >= 0f && centerX <= pageWidthPx
            if (!isVisible) continue

            lines += PageTextLine(
                text = lineText,
                left = left / PAGE_RENDER_SCALE,
                top = top / PAGE_RENDER_SCALE,
                width = (right - left) / PAGE_RENDER_SCALE,
                height = (bottom - top) / PAGE_RENDER_SCALE,
                baseline = (origin.y + layout.getLineBaseline(line)) / PAGE_RENDER_SCALE
            )
        }
    }
    return lines
}

/**
 * Lays the text out again the way it was drawn.
 *
 * The layout text nodes report through semantics is rebuilt without resolving the style's defaults, so
 * on some platforms its fonts, and so its line widths and breaks, differ from the drawn text. Measuring
 * with a [TextMeasurer] resolves them the same way drawing does.
 *
 * That layout also drops the minimum width the text was measured with, which decides where aligned text
 * that doesn't wrap goes. [drawnWidthPx], the width the text was drawn at, restores it.
 */
private fun TextLayoutResult.remeasured(drawnWidthPx: Int?): TextLayoutResult {
    val input = layoutInput
    val constraints = if (drawnWidthPx != null) {
        input.constraints.copy(minWidth = drawnWidthPx.coerceIn(0, input.constraints.maxWidth))
    } else {
        input.constraints
    }
    return TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
        text = input.text,
        style = input.style,
        overflow = input.overflow,
        softWrap = input.softWrap,
        maxLines = input.maxLines,
        placeholders = input.placeholders,
        constraints = constraints
    )
}
