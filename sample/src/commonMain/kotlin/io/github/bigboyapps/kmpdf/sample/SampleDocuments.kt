package io.github.bigboyapps.kmpdf.sample

import androidx.compose.ui.unit.dp
import io.github.bigboyapps.kmpdf.PageSize
import io.github.bigboyapps.kmpdf.PdfConfig
import io.github.bigboyapps.kmpdf.PdfMargins
import io.github.bigboyapps.kmpdf.PdfMetadata
import io.github.bigboyapps.kmpdf.PdfPageScope
import kotlin.time.Duration.Companion.seconds

/**
 * The configuration for a sample, including metadata with characters that need escaping, so every
 * generated PDF exercises the metadata encoding.
 */
fun sampleConfig(
    type: SampleType,
    pageSize: PageSize = PageSize.A4,
    margins: PdfMargins = PdfMargins.None,
    fileName: String = "sample_${type.name.lowercase()}.pdf",
    outputDirectory: String? = null
): PdfConfig = PdfConfig(
    pageSize = pageSize,
    fileName = fileName,
    outputDirectory = outputDirectory,
    margins = margins,
    // The timeout sample is meant to fail, so don't make anyone wait ten seconds for it
    contentTimeout = if (type == SampleType.NEVER_LOADS) 3.seconds else 10.seconds,
    metadata = PdfMetadata(
        title = "KmPDF sample: ${type.title} – résumé ✓",
        author = "Jane (Finance) Doe \\ Co",
        subject = "Generated from Compose UI",
        keywords = "kmpdf, sample, ${type.name.lowercase()}",
        creator = "KmPDF Sample App"
    )
)

/** The pages of each sample, shared by the app and its tests. */
fun PdfPageScope.sampleDocument(type: SampleType) {
    when (type) {
        SampleType.DEFAULT -> page { SamplePdfContent() }

        SampleType.LONG_TABLE -> repeat(3) { pageIndex -> page { LongTablePage(pageIndex + 1) } }

        SampleType.MIXED_CONTENT -> {
            page { MixedContentPage(1) }
            page { MixedContentPage(2) }
        }

        // A narrow page that's exactly as tall as the receipt
        SampleType.RECEIPT -> page(size = PageSize.wrapHeight(280.dp)) { ReceiptContent() }

        SampleType.PAGINATED_INVOICE -> pages(
            items = sampleInvoiceRows,
            header = { InvoiceHeader() },
            footer = { info -> InvoiceFooter(info) }
        ) { row ->
            InvoiceRowContent(row)
        }

        SampleType.SELECTABLE_TEXT -> page { SelectableTextShowcase() }

        SampleType.GRAPHICS -> page { GraphicsShowcase() }

        SampleType.ASYNC_CONTENT -> page { AsyncContentShowcase() }

        SampleType.MIXED_SIZES -> {
            page { MixedSizesPage("A4 portrait", "595 x 842 pt, the configured size") }
            page(size = PageSize(width = 792.dp, height = 612.dp)) {
                MixedSizesPage("Letter landscape", "792 x 612 pt, set on this page only")
            }
            page(size = PageSize(width = 612.5.dp, height = 400.25.dp)) {
                MixedSizesPage("Fractional size", "612.5 x 400.25 pt, kept exactly on every platform")
            }
            page(size = PageSize.wrapHeight(400.dp)) {
                MixedSizesPage("Content-sized", "400 pt wide, as tall as this content plus margins")
            }
        }

        SampleType.NEVER_LOADS -> page { NeverLoadsShowcase() }
    }
}
