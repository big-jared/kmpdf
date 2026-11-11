package io.github.bigboyapps.kmpdf

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PdfMarginsTest {
    @Test
    fun presetsHaveTheDocumentedValues() {
        assertEquals(PdfMargins(0.dp, 0.dp, 0.dp, 0.dp), PdfMargins.None)
        assertEquals(PdfMargins(36.dp, 36.dp, 36.dp, 36.dp), PdfMargins.Narrow)
        assertEquals(PdfMargins(72.dp, 72.dp, 72.dp, 72.dp), PdfMargins.Normal)
        assertEquals(PdfMargins(left = 144.dp, top = 72.dp, right = 144.dp, bottom = 72.dp), PdfMargins.Wide)
    }

    @Test
    fun factoriesSetTheRightSides() {
        assertEquals(PdfMargins(10.dp, 10.dp, 10.dp, 10.dp), PdfMargins.all(10.dp))
        assertEquals(PdfMargins(left = 20.dp, top = 5.dp, right = 20.dp, bottom = 5.dp), PdfMargins.symmetric(horizontal = 20.dp, vertical = 5.dp))
    }

    @Test
    fun negativeOrUnspecifiedMarginsAreRejected() {
        assertFailsWith<IllegalArgumentException> { PdfMargins(left = (-1).dp) }
        assertFailsWith<IllegalArgumentException> { PdfMargins(bottom = (-0.5).dp) }
        assertFailsWith<IllegalArgumentException> { PdfMargins(top = Dp.Unspecified) }
    }

    @Test
    fun contentAreaMustRemain() {
        assertNull(PdfMargins.Wide.contentAreaError(595f, 842f))
        assertNotNull(PdfMargins.symmetric(horizontal = 297.5.dp).contentAreaError(595f, 842f), "Zero-width content area")
        assertNotNull(PdfMargins(top = 500.dp, bottom = 400.dp).contentAreaError(595f, 842f), "Negative-height content area")
    }

    @Test
    fun configDefaultsToNoMarginsAndCopiesKeepThem() {
        assertEquals(PdfMargins.None, PdfConfig().margins)
        val config = PdfConfig(fileName = "a.pdf", margins = PdfMargins.Normal)
        assertEquals(PdfMargins.Normal, config.copy(fileName = "b.pdf").margins)
    }
}
