package io.github.bigboyapps.kmpdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bigboyapps.kmpdf.testing.RgbaImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.time.Duration.Companion.minutes

/**
 * A host Activity for rendering pages in instrumented tests. It deliberately never calls setContentView,
 * so the generator must work without view tree owners from the host.
 */
class PdfTestActivity : ComponentActivity()

/** Runs the shared generator contract on Android, reading PDFs back with PdfRenderer. */
class AndroidPdfGeneratorContractTest : PdfGeneratorContract() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pdfDirectory get() = File(context.cacheDir, "pdfs")
    private lateinit var scenario: ActivityScenario<PdfTestActivity>

    @BeforeTest
    fun launchActivity() {
        pdfDirectory.listFiles { file -> file.name.startsWith("contract-") }?.forEach { it.delete() }
        scenario = ActivityScenario.launch(PdfTestActivity::class.java)
        // Make sure the generator uses this Activity even if another test recreated it
        scenario.onActivity { initKmPdfGenerator(it) }
    }

    @AfterTest
    fun closeActivity() {
        scenario.close()
    }

    override fun runPdfTest(block: suspend CoroutineScope.() -> Unit): TestResult =
        runTest(timeout = 5.minutes) { block() }

    override fun createGenerator(): KmPdfGenerator = AndroidKmPdfGenerator()

    // Android's PdfDocument only supports whole-point page sizes, so fractional sizes round up
    override fun expectedPageSizePt(width: Float, height: Float): Pair<Float, Float> =
        ceil(width) to ceil(height)

    override suspend fun readBack(result: PdfResult.Success, renderPages: Boolean): ReadBackDocument =
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(File(result.filePath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val sizes = mutableListOf<Pair<Float, Float>>()
                    val pages = mutableListOf<RgbaImage>()
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            sizes += page.width.toFloat() to page.height.toFloat()
                            if (renderPages) {
                                val width = page.width * RENDER_SCALE
                                val height = page.height * RENDER_SCALE
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pages += bitmap.toRgbaImage()
                                bitmap.recycle()
                            }
                        }
                    }
                    ReadBackDocument(sizes, pages)
                }
            }
        }

    /** Renders [content] the same way the Android generator does: a ComposeView drawn into a Bitmap. */
    override suspend fun renderReference(content: @Composable () -> Unit, widthPx: Int, heightPx: Int): RgbaImage {
        val activity = withContext(Dispatchers.Main) {
            var current: PdfTestActivity? = null
            scenario.onActivity { current = it }
            checkNotNull(current) { "Test Activity isn't available" }
        }
        return withContext(Dispatchers.Main) {
            val parent = activity.findViewById<ViewGroup>(android.R.id.content)
            val view = ComposeView(activity).apply {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(RENDER_SCALE.toFloat())) { PageRoot(content) }
                }
                alpha = 0f
                translationX = -10_000f
                translationY = -10_000f
                // The test Activity never calls setContentView, so the view tree has no owners of its own
                setViewTreeLifecycleOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
            }
            parent.addView(view, FrameLayout.LayoutParams(widthPx, heightPx))
            try {
                delay(200)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, widthPx, heightPx)
                delay(100)
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                bitmap.toRgbaImage().also { bitmap.recycle() }
            } finally {
                parent.removeView(view)
            }
        }
    }

    override fun outputExists(fileName: String): Boolean = File(pdfDirectory, fileName).exists()

    override suspend fun pageCountOf(bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        // PdfRenderer only reads from a file descriptor
        val file = File.createTempFile("contract-bytes", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
        } finally {
            file.delete()
        }
    }

    private fun Bitmap.toRgbaImage(): RgbaImage {
        val argb = IntArray(width * height)
        getPixels(argb, 0, width, 0, 0, width, height)
        return RgbaImage.fromArgb(width, height, argb)
    }
}
