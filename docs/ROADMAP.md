# KmPDF Roadmap: Professional Quality (Batch 1)

This is the definition of done for the first batch of improvements. An item is checked off only when every acceptance criterion below passes locally, the proof (test or command output) has been recorded, and the work is committed to `main` as one focused commit.

Work happens locally on `main`: no branches, PRs, pushes, or releases.

## How correctness is proven

Every platform validates its own output with its own PDF engine. A generated PDF is **read back and rendered**, then compared pixel by pixel against the same composable rendered directly (the "reference render").

| Platform | Test task | Reads the PDF with | Reference render |
|---|---|---|---|
| Desktop (JVM) | `:kmpdf:jvmTest` | PDFBox (`Loader`, `PDFRenderer`) | `ImageComposeScene` |
| iOS | `:kmpdf:iosSimulatorArm64Test` | CoreGraphics (`CGPDFDocument`, `CGContextDrawPDFPage`) | `ImageComposeScene` |
| Android | `:kmpdf:connectedAndroidTest` (emulator) | `android.graphics.pdf.PdfRenderer` | `ComposeView` drawn to a `Bitmap` |
| Web (WASM) | `:kmpdf:wasmJsBrowserTest` (headless Chrome) | Shared PDF structure reader + `DecompressionStream` to decode page images | `ImageComposeScene` |

Shared validation rules, used by every platform's tests:

- **Structure:** the file starts with `%PDF-`, ends with `%%EOF`, and parses without repair. Page count matches the pages requested. Each page's MediaBox matches the expected size (±0.01 pt; Android rounds to whole points, see below).
- **Pixels:** the read-back render is compared with the reference render at the same pixel size. A match needs a mean absolute channel difference ≤ 2.0 and ≥ 99.5% of pixels within ±24 per channel. (Different engines resample slightly differently, so the comparison allows a little tolerance but no layout differences.)
- **Negative controls:** each pixel suite also shows that a deliberately wrong reference fails the comparison: content shifted by 4 dp, a different color, and different text. If a wrong render passes, the comparison is too weak and the suite fails.
- **Page order:** multi-page tests give each page a distinct marker color and check that the pages come back in order.

## Items

### 0. Validation harness and edge cases for existing behavior
- [ ] Shared test utilities in `commonTest`: pixel comparison with the thresholds above, and a minimal PDF structure reader (header/EOF, xref offsets, page count, MediaBox, Info dictionary).
- [ ] Platform read-back suites for JVM, iOS, Android, and Web, including negative controls.
- [ ] Edge cases on **every platform**:
  - Multi-page documents come back in the right order.
  - A4, Letter, a custom landscape size, and a fractional size (612.5 × 792.25). Android pages round up to whole points, so its tests expect 613 × 793.
  - Transparent content renders on white.
  - Content larger than the page is clipped at the page edge, and the page size doesn't change.
  - An empty page list returns an error.
  - Page content that throws returns `RenderingFailed`, and no output file is left behind (no file on web).
  - Cancellation propagates `CancellationException`, and no partial file is left.
  - A 30-page A4 document generates successfully.

### 1. Margins
- [ ] `PdfConfig(margins = PdfMargins(...))` with presets `None` (default), `Narrow`, `Normal`, and `Wide`, plus custom per-side values.
- [ ] Content is laid out in the page minus the margins. Pixel tests on every platform check that the margin bands are white and the content starts exactly at the margin offset.
- [ ] Negative margins, or margins that leave no content area, are rejected with a clear error.
- [ ] The sample no longer advertises anything the library doesn't do.

### 2. Content readiness (async content)
- [ ] Before capturing a page, rendering waits until composition settles (no pending recompositions or effects) instead of capturing the first frame or waiting a fixed delay.
- [ ] `PdfContentLoading(isLoading: Boolean)` lets page content hold rendering until data or images are ready, bounded by `PdfConfig(contentTimeout = ...)` (default 10 s).
- [ ] Tests on every platform:
  - Content that changes in a `LaunchedEffect` after a delay shows the updated state in the PDF.
  - `PdfContentLoading(true)` that never clears returns `RenderingFailed` mentioning the timeout, within the timeout plus 2 s.
  - Content that's ready immediately isn't slowed down by more than 500 ms per page.

### 3. Wrap-content page height
- [ ] `PageSize.wrapHeight(width)` (and a `WrapContent` height for individual pages) makes the page exactly as tall as its content plus the vertical margins.
- [ ] Heights are rounded up to whole points, with a minimum of 1 pt. Content taller than 14,400 pt (the PDF page limit) returns `RenderingFailed` with a clear message instead of a truncated page.
- [ ] Tests on every platform check the MediaBox height against the measured content height, compare pixels, and cover a 0-height page and an over-limit page.

### 4. Automatic pagination
- [ ] `pages(items, itemSpacing, header, footer) { item -> ... }` measures every item at the content width and fills pages in order, never splitting an item across pages.
- [ ] Headers and footers get `PdfPageInfo(pageNumber, pageCount)`. `LocalPdfPageInfo` is also available inside any page's content.
- [ ] An item taller than the available page height returns `RenderingFailed` naming the item index.
- [ ] The packing logic is a pure function with `commonTest` coverage (runs on every platform): exact fits, spacing, headers and footers, varying heights, and oversized items.
- [ ] A render test on every platform: 100 items of varying heights produce the page count the packing predicts, and page read-back confirms each page's first and last item markers and its footer page number.

### 5. `readPdfBytes` on every platform
- [ ] A common `suspend fun readPdfBytes(uri: String): ByteArray` (content/file URI on Android, file path on iOS and Desktop, blob URL on web).
- [ ] Tests on every platform: the returned size equals `fileSize`, the bytes start with `%PDF-`, and the bytes parse with the platform's PDF engine.

### 6. PDF metadata
- [ ] `PdfConfig(metadata = PdfMetadata(title, author, subject, keywords, creator))`, with the producer set to `KmPDF <version>`.
- [ ] Written natively on iOS, Desktop, and Web. Android's `PdfDocument` has no metadata API, so Android appends a standards-compliant incremental update with an Info dictionary.
- [ ] Tests on every platform read the metadata back (PDFBox, `CGPDFDocumentGetInfo`, the shared structure reader). Android output is also opened with `PdfRenderer` after the incremental update, to prove the file is still valid.

## Final checks

- [ ] `./gradlew :kmpdf:jvmTest :kmpdf:iosSimulatorArm64Test :kmpdf:wasmJsBrowserTest` pass.
- [ ] `./gradlew :kmpdf:connectedAndroidTest` passes on an emulator.
- [ ] `./gradlew :kmpdf:apiCheck detekt` pass. API dumps are updated only for the intentional additions above, with no removed or changed signatures.
- [ ] The sample builds on every platform (`:sample:assembleDebug`, `:sample:compileKotlinJvm`, `:sample:wasmJsBrowserDevelopmentExecutableDistribution`, the iOS app) and demonstrates margins, wrap content, pagination, and metadata.
- [ ] The README and KDoc document every new API, including the Android whole-point rounding and the image-based (non-selectable text) output.
- [ ] Nothing is pushed, and `.claude/settings.local.json`, `.claude/launch.json`, and the local `publish.yml` deletion aren't committed.
