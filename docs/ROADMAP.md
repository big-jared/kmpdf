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
- [x] `pages(items, itemSpacing, header, footer) { item -> ... }` measures every item at the content width and fills pages in order, never splitting an item across pages.
- [x] Headers and footers get `PdfPageInfo(pageNumber, pageCount)`. `LocalPdfPageInfo` is also available inside any page's content.
- [x] An item taller than the available page height returns `RenderingFailed` naming the item index.
- [x] The packing and planning logic is covered by `commonTest` (runs on every platform): exact fits, spacing, headers and footers, varying heights, oversized items, page numbering across mixed pages, and wrap-height configs that can't paginate.
- [x] A render test on every platform: 100 items of varying heights produce the page count the packing predicts, and page read-back confirms each page's first and last item markers and its footer page number.

**Verified:** JVM, iOS simulator, Web (headless Chrome), and Android (Pixel 8a) each pass 28/28 contract tests, plus `PagePackingTest` (10/10) and `PagePlanningTest` (7/7) on every platform. The render test flows 100 items of varying heights across Letter pages with Narrow margins, a header, and a footer, then reads each page back to check its first and last item (color-coded by index) and its footer's page number and page count against the packing. `LocalPdfPageInfo` is checked on single and flowing pages. The first Android run misread an item index because the test's colors were only 2 steps apart; the encoding now keeps neighboring items at least 10 apart, and all four platforms pass. `apiCheck` shows only additions.

### 5. `readPdfBytes` on every platform
- [x] A common `suspend fun readPdfBytes(uri: String): ByteArray` (content/file URI on Android, file path on iOS and Desktop, blob URL on web).
- [x] Tests on every platform: the returned size equals `fileSize`, the bytes start with `%PDF-`, and the bytes parse with the platform's PDF engine.

**Verified:** JVM, iOS simulator, Web (headless Chrome), and Android (Pixel 8a) each pass 30/30 contract tests. For a 2-page PDF, `readPdfBytes(result.uri)` returns exactly `fileSize` bytes starting with `%PDF-`, and the platform's own engine (PDFBox, CoreGraphics, `PdfRenderer`, or the strict structure reader on web) opens them as 2 pages. Reading a missing PDF fails on every platform. On web, a failed fetch used to return the error page's bytes; it now fails. On Android, `file:` URIs like `file:/data/...` (used when no FileProvider is configured) are read correctly. `apiCheck` shows only the addition.

### 6. PDF metadata
- [x] `PdfConfig(metadata = PdfMetadata(title, author, subject, keywords, creator))`. The producer is `KmPDF <version>`, taken from the build's library version so it can't drift from releases. On iOS, CoreGraphics always writes its own producer and offers no way to change it, so iOS writes every field except the producer.
- [x] Written natively on iOS, Desktop, and Web. Android's `PdfDocument` has no metadata API, so Android appends a standards-compliant incremental update with an Info dictionary.
- [x] Non-ASCII text (accents, symbols, CJK) and characters that need escaping (parentheses, backslashes) round-trip exactly.
- [x] Tests on every platform read the metadata back (PDFBox, `CGPDFDocumentGetInfo`, the shared structure reader). Android output is also opened with `PdfRenderer` after the incremental update, to prove the file is still valid.

**Verified:** JVM, iOS simulator, Web (headless Chrome), and Android (Pixel 8a) each pass 31/31 contract tests. The metadata test writes a title with an en dash, a check mark, and accents, plus an author with parentheses and a backslash, then reads every field back with PDFBox, `CGPDFDocumentGetInfo`, or the strict structure reader. On Android that also validates the appended update's cross-reference table, and `PdfRenderer` still opens both pages. `PdfInfoTest` (5/5) checks the incremental update against PDFBox-written files, including updating twice, and checks the web writer's Info dictionary. `apiCheck` shows only additions; the removed signatures were unreleased ones added earlier in this batch, and every 1.2.0 signature is kept.

## Final checks

- [x] `./gradlew :kmpdf:jvmTest :kmpdf:iosSimulatorArm64Test :kmpdf:wasmJsBrowserTest` pass.
- [x] The Android instrumented tests pass on a device or emulator (run with `am instrument`, one test method at a time).
- [x] `./gradlew :kmpdf:apiCheck detekt` pass. API dumps are updated only for the intentional additions above, with no removed or changed signatures.
- [x] The sample builds on every platform (`:sample:assembleDebug`, `:sample:compileKotlinJvm`, `:sample:wasmJsBrowserDevelopmentExecutableDistribution`, the iOS app) and demonstrates margins, wrap content, pagination, and metadata.
- [x] The README and KDoc document every new API, including the Android whole-point rounding and the image-based (non-selectable text) output.
- [x] Nothing is pushed, and `.claude/settings.local.json`, `.claude/launch.json`, and the local `publish.yml` deletion aren't committed.

**Verified:** Final run: JVM, iOS simulator, and Web (headless Chrome) each pass 31/31 contract tests plus every shared suite (packing 10, planning 7, margins 5, structure reader 5), JVM also passes `PdfInfoTest` (5), `RasterPdfWriterTest` (6), and `DesktopKmPdfGeneratorTest` (3), and Android (Pixel 8a, Android 16) passes 31/31 contract tests plus the same shared suites, run one method at a time with `am instrument`. `apiCheck` and detekt pass. The sample builds for Android (`assembleDebug`), Desktop, Web (`wasmJsBrowserDevelopmentExecutableDistribution`), and iOS (Xcode, built without asset catalogs because Xcode's asset catalog agent fails to launch on this machine; that only removes the app icon), and now includes a receipt page sized to its content, a 100-row invoice with automatic pagination and page numbers, a margins picker, and metadata on every sample PDF. Nothing was pushed.

## Batch 2: Selectable text

Fixes [#7](https://github.com/big-jared/kmpdf/issues/7). Pages stay images, so they look exactly like the composable, and each page gets an invisible text layer, the way scanned documents are made searchable.

- [x] Every platform writes PDFs with the same writer. Platforms render pixels, collect text, compress, and save; planning, writing, and error handling are shared (`generatePdfWith`). Android no longer uses `PdfDocument`, so page sizes are exact (no whole-point rounding) and metadata is written directly instead of through an incremental update. iOS no longer uses a CoreGraphics PDF context, so it records KmPDF as the producer.
- [x] Text lines come from the page's semantics tree: every `Text`/`BasicText` line, placed where it's drawn, leaving out text that's clipped away or outside the page. On Desktop, iOS, and Web the semantics come from a `CanvasLayersComposeScene` with a semantics listener (an internal Compose API); on Android from the page's `ViewRootForTest`.
- [x] Lines are laid out again with `TextMeasurer`, because the layout text nodes report through semantics skips resolving style defaults, and on Skia that gave different fonts and line widths from the drawn text.
- [x] The text uses an embedded glyphless TrueType font (Type0, Identity-H) with a ToUnicode map, drawn in render mode 3 (invisible), sized to each line's height and stretched to its width. Characters outside the Basic Multilingual Plane, like emoji, get their own codes, so each is one glyph.
- [x] Tests: every platform extracts the text with its own engine (PDFBox `PDFTextStripper`, PDFKit, `PdfRenderer.Page.getTextContents` on Android 15+, the strict structure reader on web): Latin, accents, CJK, a wrapped paragraph, text on the right page only, clipped and off-page text left out, and no text layer on pages without text. The strict reader decodes the text through the ToUnicode map and checks that it's invisible, starts where the text is laid out, and covers the drawn ink within 4 pt. PDFBox's `TTFParser` loads the font, and its table and whole-font checksums are checked.
- [x] Known limitation, documented: right-to-left text is written in logical order, so some viewers show it reversed when extracting it.

**Verified:** JVM, iOS simulator, and Web (headless Chrome) each pass 35/35 contract tests plus every shared suite (packing 10, planning 7, margins 5, structure reader 5). JVM also passes `RasterPdfWriterTest` (12), `PdfInfoTest` (2), and `DesktopKmPdfGeneratorTest` (3). Android passes 35/35 contract tests and the 27 shared tests on an API 35 emulator (the Pixel 8a wasn't connected). The emulator's system server crashed under load partway through the first run, so the six contract tests that hadn't run were run again on their own, and all passed. `apiCheck` and detekt pass, and the sample builds for Android, Desktop, Web, and iOS. CI (`.github/workflows/build.yml`) now runs the JVM, iOS simulator, and browser tests on macOS and the Android tests on an API 35 emulator; it hasn't run yet because nothing is pushed.
