package io.github.bigboyapps.kmpdf.sample

import java.text.SimpleDateFormat
import java.util.*

actual fun getCurrentTimestamp(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    return formatter.format(Date())
}
