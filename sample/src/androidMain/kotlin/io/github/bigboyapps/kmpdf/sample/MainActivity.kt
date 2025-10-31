package io.github.bigboyapps.kmpdf.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.bigboyapps.kmpdf.initKmPdfGenerator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initKmPdfGenerator(this)
        setContent {
            App()
        }
    }
}
