package io.github.shiroel.aries

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Phone-only preferences, available in an FCM process before a core or key is opened. */
class QuietHoursSettings(private val preferences: SharedPreferences) {
    var hours by mutableStateOf(QuietHours(
        preferences.getBoolean("enabled", false),
        preferences.getInt("start", 22 * 60).coerceIn(0, 1439),
        preferences.getInt("end", 7 * 60).coerceIn(0, 1439),
    ))
        private set

    fun update(value: QuietHours) {
        preferences.edit().putBoolean("enabled", value.enabled)
            .putInt("start", value.startMinute).putInt("end", value.endMinute).apply()
        hours = value
    }

    companion object {
        fun from(context: Context) = QuietHoursSettings(
            context.applicationContext.getSharedPreferences("quiet_hours", Context.MODE_PRIVATE),
        )
    }
}
