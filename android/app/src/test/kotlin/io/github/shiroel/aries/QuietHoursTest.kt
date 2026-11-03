package io.github.shiroel.aries

import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test
    fun overnightRangeSpansMidnightAndDisabledNeverSilences() {
        val hours = QuietHours(enabled = true)
        assertFalse(hours.contains(LocalTime.of(21, 59, 59)))
        assertTrue(hours.contains(LocalTime.of(22, 0)))
        assertTrue(hours.contains(LocalTime.of(23, 59, 59)))
        assertTrue(hours.contains(LocalTime.MIDNIGHT))
        assertTrue(hours.contains(LocalTime.of(6, 59, 59)))
        assertFalse(hours.contains(LocalTime.of(7, 0)))
        assertFalse(hours.copy(enabled = false).contains(LocalTime.MIDNIGHT))
    }

    @Test
    fun equalEndpointsSilenceAllDayOnlyWhenEnabled() {
        val hours = QuietHours(true, 0, 0)
        assertTrue(hours.contains(LocalTime.MIDNIGHT))
        assertTrue(hours.contains(LocalTime.NOON))
        assertTrue(hours.contains(LocalTime.of(23, 59, 59)))
        assertFalse(hours.copy(enabled = false).contains(LocalTime.NOON))
    }

    @Test
    fun daytimeRangeIncludesItsStartAndExcludesItsEnd() {
        val hours = QuietHours(enabled = true, startMinute = 9 * 60, endMinute = 17 * 60)
        assertFalse(hours.contains(LocalTime.of(8, 59, 59)))
        assertTrue(hours.contains(LocalTime.of(9, 0)))
        assertTrue(hours.contains(LocalTime.of(16, 59, 59)))
        assertFalse(hours.contains(LocalTime.of(17, 0)))
    }
}
