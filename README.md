# KmPDF

[![Maven Central](https://img.shields.io/maven-central/v/io.github.big-jared/kmpdf.svg)](https://central.sonatype.com/artifact/io.github.big-jared/kmpdf)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.21-blue.svg?logo=kotlin)](http://kotlinlang.org)

Generate PDF documents from Compose UI on Android, iOS, and Desktop.

## Installation

```kotlin
commonMain {
    dependencies {
        implementation("io.github.big-jared:kmpdf:1.0.0")
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
generator.generatePdf {
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
generator.generatePdf {
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

### Dynamic Pages

```kotlin
generator.generatePdf {
    repeat(10) { pageIndex ->
        page {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("Page ${pageIndex + 1}")
                // Your content for each page
            }
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

- `PageSize.Letter` - 8.5" × 11" (default)
- `PageSize.A4` - 210mm × 297mm
- `PageSize.Legal` - 8.5" × 14"
- `PageSize.A3` - 297mm × 420mm
- `PageSize.A5` - 148mm × 210mm
- `PageSize.Tabloid` - 11" × 17"

## Platform Setup

### Android

Add FileProvider to `AndroidManifest.xml`:

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

## License

MIT License - Copyright (c) 2025 Jared Guttromson

See [LICENSE](LICENSE) for full details.
