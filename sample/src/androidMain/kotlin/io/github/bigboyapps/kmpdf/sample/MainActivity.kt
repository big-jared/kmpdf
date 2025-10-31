package io.github.bigboyapps.kmpdf.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.github.bigboyapps.kmpdf.initKmPdfGenerator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initKmPdfGenerator(this)
        setContent {
            Scaffold {
                Box(modifier = Modifier.padding(it)) {
                    App()
                }
            }
        }
    }
}
