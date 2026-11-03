package io.github.shiroel.aries

import java.time.LocalTime

/** A daily device-local interval, in minutes after midnight. Equal endpoints mean all day. */
data class QuietHours(val enabled: Boolean = false, val startMinute: Int = 22 * 60, val endMinute: Int = 7 * 60) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439)
    }

    fun contains(time: LocalTime): Boolean {
        val minute = time.hour * 60 + time.minute
        return enabled && if (startMinute < endMinute) {
            minute >= startMinute && minute < endMinute
        } else {
            minute >= startMinute || minute < endMinute
        }
    }
}
