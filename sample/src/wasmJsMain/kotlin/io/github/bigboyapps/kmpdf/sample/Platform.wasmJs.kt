package io.github.bigboyapps.kmpdf.sample

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
actual fun getCurrentTimestamp(): String {
    // Colons aren't allowed in downloaded file names
    return Clock.System.now().toString().substringBefore('.').replace(':', '-')
}
