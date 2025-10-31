# KmPDF - Kotlin Multiplatform PDF Generator

A lightweight, open-source Kotlin Multiplatform library for generating PDF documents with QR codes from Compose UI. Built for mobile (Android & iOS), desktop (JVM), and web (WASM) with seamless platform-specific implementations.

## Features

- ✅ **Kotlin Multiplatform** - Share code across Android, iOS, Desktop (JVM), and WASM
- ✅ **Compose Multiplatform** - Integrates seamlessly with Compose UI
- ✅ **QR Code Generation** - Built-in QR code support using [qrose](https://github.com/alexzhirkevich/qrose)
- ✅ **Platform Native** - Uses platform-specific PDF APIs for optimal performance
- ✅ **Simple API** - Clean, intuitive interface
- ✅ **Customizable** - Configure title, subtitle, additional info, and footer
- ✅ **Future: Composable Rendering** - Designed for future support of rendering any Composable to PDF
- ✅ **MIT Licensed** - Free for commercial and personal use

## Supported Platforms

| Platform | Status | Implementation |
|----------|--------|----------------|
| Android | ✅ Full Support | `android.graphics.pdf.PdfDocument` |
| iOS | ✅ Full Support | `UIGraphics` PDF context |
| Desktop (JVM) | 🚧 Planned | Coming soon |
| WASM | 🚧 Planned | Coming soon |

## Installation

### Gradle (Kotlin DSL)

Add the library to your `commonMain` dependencies:

```kotlin
sourceSets {
    commonMain.dependencies {
        implementation("io.github.bigboiapps:kmpdf:1.0.0")
    }
}
```

Or add it as a local module:

```kotlin
// settings.gradle.kts
include(":kmpdf")

// build.gradle.kts
sourceSets {
    commonMain.dependencies {
        implementation(project(":kmpdf"))
    }
}
```

## Quick Start

### 1. Initialize (Android Only)

For Android, initialize the library in your `Application` class or `MainActivity`:

```kotlin
import io.github.bigboiapps.kmpdf.initKmPdfGenerator

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKmPdfGenerator(this)
    }
}
```

iOS, Desktop, and WASM require no initialization.

### 2. Generate a PDF

```kotlin
import io.github.bigboiapps.kmpdf.*

suspend fun generateGameQrPdf() {
    val generator = createKmPdfGenerator()

    val content = PdfContent(
        title = "Basketball Game",
        subtitle = "March 15, 2024 at 6:00 PM",
        qrCodeData = "https://joinpickup.app/game/abc123",
        additionalInfo = listOf(
            "Location: Central Park",
            "Players: 8/10",
            "Skill Level: Intermediate"
        ),
        footer = "Scan the QR code to join"
    )

    when (val result = generator.generatePdf(content, "game_invite.pdf")) {
        is PdfResult.Success -> {
            println("PDF saved at: ${result.uri}")
        }
        is PdfResult.Error -> {
            println("Error: ${result.message}")
        }
    }
}
```

### 3. Share the PDF

Use platform-specific share functionality to share the generated PDF:

**Android:**
```kotlin
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "application/pdf"
    putExtra(Intent.EXTRA_STREAM, Uri.parse(result.uri))
}
context.startActivity(Intent.createChooser(intent, "Share PDF"))
```

**iOS:**
```swift
let url = URL(fileURLWithPath: pdfPath)
let activityVC = UIActivityViewController(activityItems: [url], applicationActivities: nil)
present(activityVC, animated: true)
```

## API Reference

### `KmPdfGenerator`

Main interface for generating PDFs.

```kotlin
interface KmPdfGenerator {
    suspend fun generatePdf(
        content: PdfContent,
        fileName: String = "document.pdf"
    ): PdfResult
}
```

### `PdfContent`

Data class containing the content for the PDF.

```kotlin
data class PdfContent(
    val title: String,                      // Main title (bold, 24pt)
    val subtitle: String? = null,           // Optional subtitle (16pt)
    val qrCodeData: String,                 // Data to encode in QR code
    val additionalInfo: List<String> = emptyList(),  // Additional text lines
    val footer: String? = null              // Optional footer text
)
```

### `PdfResult`

Sealed class representing the result of PDF generation.

```kotlin
sealed class PdfResult {
    data class Success(val uri: String) : PdfResult()
    data class Error(val message: String, val exception: Throwable? = null) : PdfResult()
}
```

### Factory Function

```kotlin
expect fun createKmPdfGenerator(): KmPdfGenerator
```

Creates a platform-specific instance of `KmPdfGenerator`.

## Example: Complete Integration

Here's a complete example integrating with a Compose UI:

```kotlin
@Composable
fun ShareGameScreen(game: Game) {
    val scope = rememberCoroutineScope()
    val generator = remember { createKmPdfGenerator() }
    var pdfUri by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = game.title,
            style = MaterialTheme.typography.headlineMedium
        )

        PrimaryButton(
            text = if (isGenerating) "Generating PDF..." else "Print to PDF",
            enabled = !isGenerating,
            onClick = {
                scope.launch {
                    isGenerating = true
                    val content = PdfContent(
                        title = game.title,
                        subtitle = "${game.date} at ${game.time}",
                        qrCodeData = "https://joinpickup.app/game/${game.id}",
                        additionalInfo = listOf(
                            "Location: ${game.parkName}",
                            "Players: ${game.currentPlayers}/${game.maxPlayers}"
                        ),
                        footer = "Scan to join the game"
                    )

                    when (val result = generator.generatePdf(content)) {
                        is PdfResult.Success -> {
                            pdfUri = result.uri
                        }
                        is PdfResult.Error -> {
                            println("Error: ${result.message}")
                        }
                    }
                    isGenerating = false
                }
            }
        )

        pdfUri?.let { uri ->
            Text("PDF generated successfully!")
            PrimaryButton(
                text = "Share PDF",
                onClick = { sharePdf(uri) }
            )
        }
    }
}
```

## Platform Details

### Android
- Uses `android.graphics.pdf.PdfDocument` for PDF generation
- Uses native Android graphics APIs for QR code rendering
- PDFs are saved to the app's cache directory
- Minimum SDK: 26 (Android 8.0)

### iOS
- Uses `UIGraphics` PDF context for PDF generation
- Simple QR code rendering for iOS compatibility
- PDFs are saved to the documents directory
- Minimum iOS: 13.0

### Desktop (JVM)
- 🚧 Coming soon
- Will use Java PDF libraries (e.g., Apache PDFBox or similar)

### WASM
- 🚧 Coming soon
- Will use browser APIs for PDF generation

## Future Enhancements

This library is designed with extensibility in mind. Future versions will support:

- **Composable-to-PDF**: Render any `@Composable` function directly to PDF
- **Custom Styling**: More granular control over fonts, colors, and layout
- **Multi-page PDFs**: Support for documents with multiple pages
- **Templates**: Pre-built templates for common use cases
- **Advanced QR Codes**: Enhanced QR code generation with logos and custom styling
- **Desktop & WASM Support**: Full implementation for all platforms

## Contributing

Contributions are welcome! This library is open source under the MIT License.

### Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/big-jared/kmpdf.git
   cd kmpdf
   ```

2. Open in Android Studio or IntelliJ IDEA

3. Build the project:
   ```bash
   ./gradlew :kmpdf:build
   ```

### Running Tests

```bash
./gradlew :kmpdf:test
```

### Publishing

To publish the library locally:

```bash
./gradlew :kmpdf:publishToMavenLocal
```

## License

```
MIT License

Copyright (c) 2025 BigBoi Apps

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Credits

Built with:
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)
- [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
- [qrose](https://github.com/alexzhirkevich/qrose) - QR code generation library

## Support

For issues, questions, or contributions, please visit the [GitHub repository](https://github.com/big-jared/kmpdf).
