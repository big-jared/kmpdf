package io.github.bigboyapps.kmpdf.sample

import kotlin.js.Date

actual fun getCurrentTimestamp(): String {
    val date = Date()
    return date.toLocaleString()
}
