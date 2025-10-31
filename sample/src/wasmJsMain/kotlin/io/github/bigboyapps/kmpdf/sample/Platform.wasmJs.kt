package io.github.bigboyapps.kmpdf.sample

import kotlinx.datetime.Clock

actual fun getCurrentTimestamp(): String {
    return Clock.System.now().toString()
}
