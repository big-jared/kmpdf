# KmPDF Roadmap: Professional Quality (Batch 1)

This is the definition of done for the first batch of improvements. An item is checked off only when every acceptance criterion below passes locally, the proof (test or command output) has been recorded, and the work is committed to `main` as one focused commit.

Work happens locally on `main`: no branches, PRs, pushes, or releases.

## How correctness is proven

Every platform validates its own output with its own PDF engine. A generated PDF is **read back and rendered**, then compared pixel by pixel against the same composable rendered directly (the "reference render").

| Platform | Test task | Reads the PDF with | Reference render |
|---|---|---|---|
| Desktop (JVM) | `:kmpdf:jvmTest` | PDFBox (`Loader`, `PDFRenderer`) | `ImageComposeScene` |
| iOS | `:kmpdf:iosSimulatorArm64Test` | CoreGraphics (`CGPDFDocument`, `CGContextDrawPDFPage`) | `ImageComposeScene` |
| Android | Instrumented tests (`am instrument` on a device or emulator) | `android.graphics.pdf.PdfRenderer` | `ComposeView` drawn to a `Bitmap` |
| Web (WASM) | `:kmpdf:wasmJsBrowserTest` (headless Chrome) | Shared PDF structure reader + `DecompressionStream` to decode page images | `ImageComposeScene` |

Shared validation rules, used by every platform's tests:

- **Structure:** the file starts with `%PDF-`, ends with `%%EOF`, and parses without repair. Page count matches the pages requested. Each page's MediaBox matches the expected size (±0.01 pt; Android rounds to whole points, see below).
- **Pixels:** the read-back render is compared with the reference render at the same pixel size. The image is split into 16 × 16 px tiles. A match needs a global mean absolute channel difference ≤ 2.0 **and no tile** with a mean absolute channel difference above 16. Different engines resample slightly differently, which spreads thin noise across tiles. A real content change (even one character) concentrates in a tile and fails.
- **Negative controls:** each pixel suite also shows that a deliberately wrong reference fails the comparison: content shifted by 4 dp, a different color, and a single changed character. If a wrong render passes, the comparison is too weak and the suite fails.
- **Page order:** multi-page tests give each page a distinct marker color and check that the pages come back in order.

## Items

### 0. Validation harness and edge cases for existing behavior
- [x] Shared test utilities in `commonTest`: pixel comparison with the thresholds above, and a minimal PDF structure reader (header/EOF, xref offsets, page count, MediaBox, Info dictionary).
- [x] Platform read-back suites for JVM, iOS, Android, and Web, including negative controls.
- [x] Edge cases on **every platform**:
  - Multi-page documents come back in the right order.
  - A4, Letter, a custom landscape size, and a fractional size (612.5 × 792.25). Android pages round up to whole points, so its tests expect 613 × 793.
  - Transparent content renders on white.
  - Content larger than the page is clipped at the page edge, and the page size doesn't change.
  - An empty page list returns an error.
  - Page content that throws returns `RenderingFailed`, and no output file is left behind (no file on web).
  - Cancellation propagates `CancellationException`, and no partial file is left.
  - A 30-page A4 document generates successfully.

**Verified:** JVM 10/10, iOS simulator 10/10, Web (headless Chrome) 10/10, and Android (Pixel 8a, Android 16) 10/10 contract tests, plus the structure reader tests (5/5) on every platform.

**Bugs found and fixed by the harness:**
- iOS kept writing the complete PDF after cancellation, because rendering never suspended.
- Android crashed with `ViewTreeLifecycleOwner not found` when the host Activity hadn't called `setContentView`, or was a plain `Activity`.
- Android gave page content exact page-size constraints while the other platforms gave loose ones, so `Modifier.size(50.dp)` filled the whole page on Android only. All platforms now render pages in a shared `PageRoot`.
- Android truncated fractional page sizes (612.5 → 612) instead of rounding up.
- On Android, page content that threw escaped `generatePdf` (crashing the caller) and was retried as if the Activity had been recreated.

### 1. Margins
- [x] `PdfConfig(margins = PdfMargins(...))` with presets `None` (default), `Narrow`, `Normal`, and `Wide`, plus custom per-side values.
- [x] Content is laid out in the page minus the margins. Pixel tests on every platform check that the margin bands are white and the content starts exactly at the margin offset.
- [x] Negative margins, or margins that leave no content area, are rejected with a clear error.
- [x] The sample no longer advertises anything the library doesn't do.

**Verified:** JVM, iOS simulator, Web (headless Chrome), and Android (Pixel 8a) each pass 13/13 contract tests, including three margin tests: every margin band is white with content starting at the margin edge, margin content matches the reference render and is clipped, and margins with no room for content fail without writing a file. `PdfMarginsTest` (presets, factories, validation) passes 5/5 on every platform. `apiCheck` only shows additions; the previous `PdfConfig` constructor and `copy` signatures are kept as hidden overloads, so apps built against 1.2.0 still link.

### 2. Content readiness (async content)
- [x] Before capturing a page, rendering keeps producing frames until composition settles (no pending recompositions), instead of capturing the first frame or waiting a fixed delay. Endlessly animating content never settles, so it's captured after at most 30 extra frames instead of hanging.
- [x] `PdfContentLoading(isLoading: Boolean)` holds capture while page content loads data or images asynchronously, bounded by `PdfConfig(contentTimeout = ...)` (default 10 s). Outside PDF rendering it does nothing, so the same composable works on screen. (Work that finishes after an arbitrary delay can't be detected automatically, so it needs this signal.)
- [x] Tests on every platform:
  - State changed right after composition (in a `LaunchedEffect`) shows its updated value in the PDF.
  - Content that loads for 500 ms behind `PdfContentLoading` shows its loaded state.
  - `PdfContentLoading(true)` that never clears returns `RenderingFailed` mentioning the timeout, within the timeout plus 2 s, and leaves no file.
  - An endlessly animating page still generates.
  - A static page is captured without waiting for extra frames.

**Verified:** JVM, iOS simulator, Web (headless Chrome), and Android (Pixel 8a) each pass 18/18 contract tests, including the five readiness tests. Android uses a dedicated recomposer per page instead of fixed 200 ms and 100 ms delays. iOS now writes through a CoreGraphics PDF context, so suspending between pages never leaves UIKit's shared graphics context open. `apiCheck` shows only additions; the 1.2.0 `PdfConfig` signatures, including the no-argument constructor that adding a `Duration` property would otherwise remove, are kept as hidden overloads.

### 3. Wrap-content page height
- [x] `PageSize.wrapHeight(width)`, used in `PdfConfig` or for individual pages with `page(size = ...)`, makes the page exactly as tall as its content plus the vertical margins. A document can mix page sizes.
- [x] Heights are rounded up to whole points, with a minimum of 1 pt. Content taller than 14,400 pt (the PDF page limit) returns `RenderingFailed` with a clear message instead of a truncated page.
- [x] Tests on every platform check the MediaBox height against the measured content height, compare pixels, and cover a 0-height page and an over-limit page.

**Verified:** JVM, iOS simulator, Web (headless Chrome), and Android (Pixel 8a) each pass 25/25 contract tests, including seven page-size tests: content-sized pages match the reference render, include vertical margins, round 100.3 pt of content up to 101 pt, make an empty page 1 pt tall, reject content over 14,400 pt without writing a file, measure content only after it finishes loading, and mix A4, Letter, and wrap-height pages in one document. Pages are measured in a planning step shared by every platform before they're rendered, and the web writer now supports a different size for each page. `apiCheck` shows only additions.

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
- [ ] The Android instrumented tests pass on a device or emulator (run with `am instrument`, one test method at a time).
- [ ] `./gradlew :kmpdf:apiCheck detekt` pass. API dumps are updated only for the intentional additions above, with no removed or changed signatures.
- [ ] The sample builds on every platform (`:sample:assembleDebug`, `:sample:compileKotlinJvm`, `:sample:wasmJsBrowserDevelopmentExecutableDistribution`, the iOS app) and demonstrates margins, wrap content, pagination, and metadata.
- [ ] The README and KDoc document every new API, including the Android whole-point rounding and the image-based (non-selectable text) output.
- [ ] Nothing is pushed, and `.claude/settings.local.json`, `.claude/launch.json`, and the local `publish.yml` deletion aren't committed.
