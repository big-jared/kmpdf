package io.github.bigboyapps.kmpdf.sample

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KmPDF Sample"
    ) {
        App()
    }
}
