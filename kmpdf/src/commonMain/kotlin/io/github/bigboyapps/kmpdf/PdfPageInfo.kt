package io.github.bigboyapps.kmpdf

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where a page sits in the generated document.
 *
 * @property pageNumber The page's number, starting at 1.
 * @property pageCount The total number of pages in the document.
 */
data class PdfPageInfo(val pageNumber: Int, val pageCount: Int)

/**
 * The [PdfPageInfo] of the page being rendered, available inside any page's content, for example to
 * show "Page 2 of 5". Headers and footers of [PdfPageScope.pages] also receive it directly.
 */
val LocalPdfPageInfo = staticCompositionLocalOf { PdfPageInfo(pageNumber = 1, pageCount = 1) }
