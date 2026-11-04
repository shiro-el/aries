package io.github.shiroel.aries.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLayout { CONSOLE, THREAD, TASKS }

/** Device appearance, available before the core or an SSH key has been opened. */
class ThemeSettings(private val preferences: SharedPreferences) {
    var layout by mutableStateOf(
        AppLayout.entries.find { it.name == preferences.getString("layout", null) } ?: AppLayout.CONSOLE,
    )
        private set
    var mode by mutableStateOf(
        ThemeMode.entries.find { it.name == preferences.getString("mode", null) } ?: ThemeMode.SYSTEM,
    )
        private set
    var accentHex by mutableStateOf(validHex(preferences.getString("accent", null)) ?: "#F4B73A")
        private set

    val accentArgb: Int get() = 0xFF000000.toInt() or accentHex.drop(1).toInt(16)

    fun chooseLayout(value: AppLayout) {
        preferences.edit().putString("layout", value.name).apply()
        layout = value
    }

    fun chooseMode(value: ThemeMode) {
        preferences.edit().putString("mode", value.name).apply()
        mode = value
    }

    fun setAccent(value: String): Boolean {
        val hex = validHex(value) ?: return false
        preferences.edit().putString("accent", hex).apply()
        accentHex = hex
        return true
    }

    companion object {
        private fun validHex(value: String?): String? = value
            ?.takeIf { Regex("#?[0-9a-fA-F]{6}").matches(it) }
            ?.let { "#" + it.removePrefix("#").uppercase(java.util.Locale.ROOT) }

        fun from(context: Context) = ThemeSettings(
            context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE),
        )
    }
}
