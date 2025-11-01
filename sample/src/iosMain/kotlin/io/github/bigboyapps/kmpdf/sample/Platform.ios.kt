package io.github.bigboyapps.kmpdf.sample

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

actual fun getCurrentTimestamp(): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
    return formatter.stringFromDate(NSDate())
}
