# KmPDF - Kotlin Multiplatform PDF Generator

A lightweight, open-source Kotlin Multiplatform library for generating PDF documents from Compose UI. Built for mobile (Android & iOS), desktop (JVM), and web (WASM) with platform-specific implementations.

## Features

- ✅ **Kotlin Multiplatform** - Share code across Android, iOS, Desktop (JVM), and WASM
- ✅ **Compose Multiplatform** - Render any `@Composable` to PDF
- ✅ **Platform Native** - Uses platform-specific PDF APIs for optimal performance
- ✅ **Simple API** - Clean, intuitive interface
- ✅ **Flexible** - Works with any composable content
- ✅ **MIT Licensed** - Free for commercial and personal use

## Supported Platforms

| Platform | Status | Implementation |
|----------|--------|----------------|
| Android | 🚧 In Progress | `android.graphics.pdf.PdfDocument` |
| iOS | 🚧 In Progress | `UIGraphics` PDF context |
| Desktop (JVM) | 🚧 Planned | Apache PDFBox or similar |
| WASM | 🚧 Planned | Browser PDF APIs |

## Installation

### Gradle (Kotlin DSL)

Add the library to your `commonMain` dependencies:

```kotlin
sourceSets {
    commonMain.dependencies {
        implementation("io.github.bigboyapps:kmpdf:1.0.0")
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
import io.github.bigboyapps.kmpdf.initKmPdfGenerator

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKmPdfGenerator(this)
    }
}
```

iOS, Desktop, and WASM require no initialization.

### 2. Generate a PDF from a Composable

```kotlin
import io.github.bigboyapps.kmpdf.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

suspend fun generatePdf() {
    val generator = createKmPdfGenerator()

    when (val result = generator.generatePdf(
        width = 595.dp,  // A4 width in points
        height = 842.dp, // A4 height in points
        fileName = "my_document.pdf"
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = "Basketball Game",
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "March 15, 2024 at 6:00 PM",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Add any composable content
            GameDetails(game)
            QrCodeImage(gameUrl)
        }
    }) {
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

Main interface for generating PDFs from composables.

```kotlin
interface KmPdfGenerator {
    suspend fun generatePdf(
        width: Dp = 595.dp,
        height: Dp = 842.dp,
        fileName: String = "document.pdf",
        content: @Composable () -> Unit
    ): PdfResult
}
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

## Example: Game Invitation PDF

Here's a complete example creating a game invitation PDF:

```kotlin
@Composable
fun GameInvitationPdf(game: Game) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = game.title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${game.date} at ${game.time}",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        // QR Code (using qrose library separately)
        QrCodeImage(
            data = "https://joinpickup.app/game/${game.id}",
            size = 200.dp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoRow("Location", game.parkName)
            InfoRow("Players", "${game.currentPlayers}/${game.maxPlayers}")
            InfoRow("Skill Level", game.skillLevel)
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Scan the QR code to join the game",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

suspend fun shareGamePdf(game: Game) {
    val generator = createKmPdfGenerator()
    when (val result = generator.generatePdf(fileName = "game_${game.id}.pdf") {
        GameInvitationPdf(game)
    }) {
        is PdfResult.Success -> sharePdf(result.uri)
        is PdfResult.Error -> showError(result.message)
    }
}
```

## Platform Details

### Android
- Uses `android.graphics.pdf.PdfDocument` for PDF generation
- 🚧 Composable rendering coming soon
- PDFs are saved to the app's cache directory
- Minimum SDK: 26 (Android 8.0)

### iOS
- Uses `UIGraphics` PDF context for PDF generation
- 🚧 Composable rendering coming soon
- PDFs are saved to the documents directory
- Minimum iOS: 13.0

### Desktop (JVM)
- 🚧 Coming soon
- Will use Apache PDFBox or similar library

### WASM
- 🚧 Coming soon
- Will use browser APIs for PDF generation

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

Copyright (c) 2025 BigBoy Apps

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

## Support

For issues, questions, or contributions, please visit the [GitHub repository](https://github.com/big-jared/kmpdf).
