package io.github.shiroel.aries.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class LockDelay(val milliseconds: Long) {
    IMMEDIATE(0), ONE_MINUTE(60_000), FIVE_MINUTES(300_000),
}

/** Process-local access; only the policy survives a restart, never an unlocked session. */
class AppLock(
    private val preferences: SharedPreferences,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    var enabled by mutableStateOf(preferences.getBoolean("enabled", false))
        private set
    var delay by mutableStateOf(
        LockDelay.entries.find { it.name == preferences.getString("delay", null) } ?: LockDelay.IMMEDIATE,
    )
        private set
    var hidePreviews by mutableStateOf(preferences.getBoolean("hide_previews", true))
        private set
    var locked by mutableStateOf(enabled)
        private set
    private var backgroundAt: Long? = null

    fun enableAfterAuthentication() {
        preferences.edit().putBoolean("enabled", true).apply()
        enabled = true
        authenticationSucceeded()
    }

    fun disable() {
        preferences.edit().putBoolean("enabled", false).apply()
        enabled = false
        authenticationSucceeded()
    }

    fun chooseHidePreviews(value: Boolean) {
        preferences.edit().putBoolean("hide_previews", value).apply()
        hidePreviews = value
    }

    fun chooseDelay(value: LockDelay) {
        preferences.edit().putString("delay", value.name).apply()
        delay = value
    }

    fun authenticationSucceeded() {
        // A result queued just before Home must not reopen content behind the lock.
        // Only a foreground entry consumes the background timestamp.
        locked = enabled && backgroundAt != null
    }

    fun backgrounded() {
        if (backgroundAt == null) backgroundAt = elapsedRealtime()
        if (enabled && delay == LockDelay.IMMEDIATE) locked = true
    }

    fun foregrounded() {
        backgroundAt?.let { since ->
            if (enabled && elapsedRealtime() - since >= delay.milliseconds) locked = true
        }
        backgroundAt = null
    }

    companion object {
        fun from(context: Context) = AppLock(
            context.applicationContext.getSharedPreferences("app_lock", Context.MODE_PRIVATE),
        )
    }
}
