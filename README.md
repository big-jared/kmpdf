# KmPDF

[![Maven Central](https://img.shields.io/maven-central/v/io.github.big-jared/kmpdf.svg)](https://central.sonatype.com/artifact/io.github.big-jared/kmpdf)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.21-blue.svg?logo=kotlin)](http://kotlinlang.org)

Generate PDF documents from Compose UI on Android, iOS, Desktop, and Web.

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

### Single Page

```kotlin
generator.generatePdf(
    config = PdfConfig(
        pageSize = PageSize.A4,
        fileName = "document.pdf"
    )
) {
    page {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("My Document", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            Text("Content goes here")
        }
    }
}
```

### Multiple Pages

```kotlin
generator.generatePdf(
    config = PdfConfig(fileName = "multi-page.pdf")
) {
    page {
        Text("Page 1")
    }
    page {
        Text("Page 2")
    }
    page {
        Text("Page 3")
    }
}
```

### Generating Pages Programmatically

Use loops or any Kotlin logic to create pages from data:

```kotlin
generator.generatePdf(
    config = PdfConfig(fileName = "report.pdf")
) {
    items.forEach { item ->
        page {
            ItemContent(item)
        }
    }
}
```

## Configuration

```kotlin
PdfConfig(
    pageSize = PageSize.A4,           // A4, Letter, Legal, A3, A5, Tabloid
    fileName = "report.pdf",          // Output filename
    outputDirectory = "/custom/path"  // Optional, Desktop only (defaults to ~/Documents/pdfs/)
)
```

### Available Page Sizes

- `PageSize.A4` - 210mm × 297mm (default)
- `PageSize.Letter` - 8.5" × 11"
- `PageSize.Legal` - 8.5" × 14"
- `PageSize.A3` - 297mm × 420mm
- `PageSize.A5` - 148mm × 210mm
- `PageSize.Tabloid` - 11" × 17"

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

No additional setup required.

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
    is PdfResult.Error -> {
        println("Error: ${result.message}")
    }
}
```

## Requirements

- Kotlin 2.2.21+
- Compose Multiplatform 1.9.2+
- Android: minSdk 26
- iOS: iOS 14.0+
- Desktop: JVM 17+
- Web: a browser with WebAssembly GC support

## License

MIT License - Copyright (c) 2025 Jared Guttromson

See [LICENSE](LICENSE) for full details.
