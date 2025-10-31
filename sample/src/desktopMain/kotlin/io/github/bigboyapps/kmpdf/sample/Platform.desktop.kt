package io.github.bigboyapps.kmpdf.sample

import java.text.SimpleDateFormat
import java.util.*

actual fun getCurrentTimestamp(): String {
    val formatter = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
    return formatter.format(Date())
}
