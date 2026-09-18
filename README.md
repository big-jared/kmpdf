# KmPDF

[![Maven Central](https://img.shields.io/maven-central/v/io.github.big-jared/kmpdf.svg)](https://central.sonatype.com/artifact/io.github.big-jared/kmpdf)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.21-blue.svg?logo=kotlin)](http://kotlinlang.org)

Generate PDF documents from Compose UI on Android, iOS, Desktop, and Web.

- Render any `@Composable` as a PDF page
- Text can be selected, searched, and copied
- Page sizes, margins, and pages sized to their content
- Automatic pagination with headers, footers, and page numbers
- Waits for content that loads asynchronously
- Document metadata (title, author, and more)

## Platform Support

| Platform | Status | Notes |
|----------|--------|-------|
| Android | ✅ Supported | API 26+ (Android 8.0+) |
| iOS | ✅ Supported | iOS 14.0+ (iosArm64, iosX64, iosSimulatorArm64) |
| Desktop (JVM) | ✅ Supported | JVM 17+ (macOS, Windows, Linux) |
| Web (WASM) | ✅ Supported | Browsers with WebAssembly GC (Chrome 119+, Firefox 120+, Safari 18.2+) |

## Installation

```kotlin
commonMain {
    dependencies {
        implementation("io.github.big-jared:kmpdf:1.2.0")
    }
}
```

## Quick Start

```kotlin
val generator = createKmPdfGenerator()

val result = generator.generatePdf(
    config = PdfConfig(
        pageSize = PageSize.Letter,
        fileName = "my-document.pdf"
    )
) {
    page {
        Text("Hello, PDF!")
    }
    page {
        Text("Page 2 content")
    }
}

when (result) {
    is PdfResult.Success -> sharePdf(result.uri)
    is PdfResult.Error -> println(result.message)
}
```

## Usage

### Pages

Each `page { }` renders its content exactly as provided, at the configured page size:

```kotlin
generator.generatePdf(config = PdfConfig(fileName = "report.pdf")) {
    page {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("My Document", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            Text("Content goes here")
        }
    }
    items.forEach { item ->
        page { ItemContent(item) }
    }
}
```

Content larger than the page is clipped at the page edge.

### Margins

Content is laid out inside the margins and clipped to them:

```kotlin
PdfConfig(margins = PdfMargins.Normal)                                // 1 inch on every side
PdfConfig(margins = PdfMargins.symmetric(horizontal = 36.dp, vertical = 72.dp))
PdfConfig(margins = PdfMargins(left = 36.dp, top = 72.dp, right = 36.dp, bottom = 54.dp))
```

Presets: `None` (default), `Narrow` (0.5 in), `Normal` (1 in), and `Wide` (1 in top and bottom, 2 in left and right). Values are in points (1 point = 1/72 inch). Negative margins throw, and margins that leave no room for content return an error.

### Page Sizes

Built-in sizes: `PageSize.A4` (default), `Letter`, `Legal`, `A3`, `A5`, and `Tabloid`, or any `PageSize(width, height)` in points.

A page can override the document's size, so one PDF can mix sizes:

```kotlin
generator.generatePdf(config = PdfConfig(pageSize = PageSize.A4)) {
    page { CoverPage() }                                  // A4
    page(size = PageSize.Letter) { Appendix() }           // Letter
    page(size = PageSize.wrapHeight(280.dp)) { Receipt() } // As tall as the receipt
}
```

`PageSize.wrapHeight(width)` makes a page exactly as tall as its content plus the vertical margins, rounded up to whole points. It's useful for receipts, tickets, and long single pages. Content taller than 14,400 points (the largest page a PDF can have) returns an error.

### Automatic Pagination

`pages()` flows a list of items across as many pages as they need, in order, without ever splitting an item across pages. Headers and footers repeat on every page and receive the page number:

```kotlin
generator.generatePdf(config = PdfConfig(pageSize = PageSize.Letter, margins = PdfMargins.Narrow)) {
    pages(
        items = invoiceLines,
        itemSpacing = 4.dp,
        header = { Text("Invoice #1234", style = MaterialTheme.typography.headlineSmall) },
        footer = { info -> Text("Page ${info.pageNumber} of ${info.pageCount}") }
    ) { line ->
        InvoiceLineRow(line)
    }
}
```

Page numbers count across the whole document. Inside any page's content, `LocalPdfPageInfo.current` gives the same `PdfPageInfo`. An item taller than the space available on a page returns an error, and `pages()` needs a fixed page height (not `wrapHeight`).

### Content That Loads Asynchronously

Pages are captured once their composition settles, so state set right after composition (for example in a `LaunchedEffect`) is included. For content that finishes later, like images from the network or data from a database, report it with `PdfContentLoading` and the page waits:

```kotlin
page {
    val invoice by produceState<Invoice?>(null) { value = repository.loadInvoice() }
    PdfContentLoading(isLoading = invoice == null)
    invoice?.let { InvoiceContent(it) }
}
```

Generation waits up to `PdfConfig.contentTimeout` (10 seconds by default) and then returns `PdfResult.Error.RenderingFailed`. Outside PDF generation `PdfContentLoading` does nothing, so the same composable can be shown on screen. Endlessly animating content is captured after a short settling period instead of hanging.

### Metadata

```kotlin
PdfConfig(
    metadata = PdfMetadata(
        title = "Quarterly Report",
        author = "Finance Team",
        subject = "Q3 revenue",
        keywords = "finance, q3",
        creator = "My App"
    )
)
```

PDF viewers show these in the document's properties. KmPDF also records itself as the producer.

### Reading the PDF Bytes

`readPdfBytes` reads a generated PDF on every platform, for example to upload it:

```kotlin
when (val result = generator.generatePdf { page { Receipt() } }) {
    is PdfResult.Success -> api.upload(readPdfBytes(result.uri))
    is PdfResult.Error -> showError(result.message)
}
```

## Configuration

```kotlin
PdfConfig(
    pageSize = PageSize.A4,                       // Or PageSize.wrapHeight(width)
    fileName = "report.pdf",                      // Output file name
    outputDirectory = "/custom/path",             // Desktop only (defaults to ~/Documents/pdfs/)
    margins = PdfMargins.Normal,                  // Defaults to PdfMargins.None
    contentTimeout = 10.seconds,                  // How long PdfContentLoading can hold a page
    metadata = PdfMetadata(title = "Report")      // Defaults to none
)
```

## Platform Setup

### Android

Since 1.1.0, KmPDF initializes automatically at app startup through a `ContentProvider`, so no setup code is needed.

On 1.0.0, or if you've removed the `KmPdfInitializer` provider from your manifest, initialize KmPDF in your Activity's `onCreate()`:

```kotlin
import io.github.bigboyapps.kmpdf.initKmPdfGenerator

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    initKmPdfGenerator(this)
    // ...
}
```

Add FileProvider to your app's `AndroidManifest.xml` for sharing PDFs:

```xml
<application>
    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.fileprovider"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data
            android:name="android.support.FILE_PROVIDER_PATHS"
            android:resource="@xml/file_paths" />
    </provider>
</application>
```

Create `res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="pdfs" path="pdfs/" />
</paths>
```

### iOS

No additional setup required. PDFs are saved to the app's `Documents/pdfs/` directory.

### Desktop (JVM)

PDFs are saved to `~/Documents/pdfs/` by default. You can specify a custom output directory using the `outputDirectory` parameter in `PdfConfig`.

### Web (WASM)

No additional setup required. Browsers have no file system, so the PDF is kept in memory:

- `result.uri` is a `blob:` URL and `result.filePath` is the file name from `PdfConfig`
- `sharePdf(result.uri)` downloads the file, then frees it from memory about a minute later

To keep the PDF around instead, the web target also provides:

```kotlin
downloadPdf(result.uri, fileName = "custom-name.pdf")  // Download without freeing it
val bytes: ByteArray = readPdfBytes(result.uri)        // Read the PDF, e.g. to upload it
releasePdf(result.uri)                                  // Free the memory when you're done
```

Each generated PDF stays in memory until `sharePdf` or `releasePdf` is called for it.

## Error Handling

```kotlin
when (result) {
    is PdfResult.Success -> {
        println("PDF: ${result.filePath}")
        println("${result.pageCount} pages, ${result.fileSize} bytes")
    }
    is PdfResult.Error.RenderingFailed -> println("A page couldn't be rendered: ${result.message}")
    is PdfResult.Error.IOError -> println("The PDF couldn't be written: ${result.message}")
    is PdfResult.Error -> println("Error: ${result.message}")
}
```

- `RenderingFailed`: page content threw, content was still loading after `contentTimeout`, or content was too tall for a page
- `IOError`: the PDF couldn't be written
- `NotInitialized`: Android only, when the automatic initializer was removed and `initKmPdfGenerator` wasn't called
- `Unknown`: an empty document or an invalid configuration, such as margins with no room for content

Cancelling the coroutine that calls `generatePdf` cancels generation, and no partial PDF is left behind.

## Selectable Text

Each page is rendered at 2 pixels per point and embedded as an image, so the PDF looks exactly like your composable. Over the image, KmPDF writes the page's text invisibly, line by line, in the same place it's drawn. That's how scanned documents are made searchable, and it lets people select, search, and copy the text in any PDF viewer.

Text comes from `Text` and `BasicText` (anything that exposes its text layout to accessibility). Text that's clipped away or outside the page is left out. Text drawn directly on a `Canvas`, or inside an image, isn't included.

## Limitations

- **Pages are images with a text layer.** Text can be selected and searched, but it isn't vector text: it doesn't stay sharp at very high zoom, and each page is stored as an image, so files are larger than a PDF written with fonts.
- **Right-to-left text is extracted in logical order.** Copying Arabic or Hebrew gives the right text, but some viewers show it reversed when searching or extracting it.
- **Compose version.** On Desktop, iOS, and Web, the text layer reads the page's semantics through an internal Compose API, so KmPDF is tied to the Compose Multiplatform version it's built with (1.9.x). A newer Compose version may need a KmPDF update.
- **No automatic splitting inside a composable.** `pages()` paginates a list of items; a single composable taller than a page is clipped (or use `PageSize.wrapHeight`).

## Testing

Every platform runs the same contract suite, which reads each generated PDF back with that platform's own PDF engine (PDFBox, CoreGraphics and PDFKit, Android's `PdfRenderer`, or a strict PDF structure reader on the web). It compares every page pixel by pixel with a direct render of the same composable, and checks that the text can be extracted and lines up with the drawn text:

```bash
./gradlew :kmpdf:jvmTest :kmpdf:iosSimulatorArm64Test :kmpdf:wasmJsBrowserTest
```

Android tests run on a device or emulator with `./gradlew :kmpdf:connectedDebugAndroidTest`.

## Requirements

- Kotlin 2.2.21+
- Compose Multiplatform 1.9.x
- Android: minSdk 26
- iOS: iOS 14.0+
- Desktop: JVM 17+
- Web: a browser with WebAssembly GC support

## License

MIT License - Copyright (c) 2025 Jared Guttromson

See [LICENSE](LICENSE) for full details.
