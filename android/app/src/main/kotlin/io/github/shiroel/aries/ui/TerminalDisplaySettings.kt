package io.github.shiroel.aries.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class TerminalFont { SYSTEM, JETBRAINS_MONO }

/** Device-local defaults shared by all Terminals; these never resize a Host's observe grid. */
class TerminalDisplaySettings(private val preferences: SharedPreferences) {
    var fontSize by mutableIntStateOf(preferences.getInt("font_size", 14).coerceIn(8, 32))
        private set
    var font by mutableStateOf(
        TerminalFont.entries.find { it.name == preferences.getString("font", null) } ?: TerminalFont.SYSTEM,
    )
        private set
    var minimumContrast by mutableStateOf(preferences.getBoolean("minimum_contrast", false))
        private set

    fun chooseFontSize(value: Int) {
        fontSize = value.coerceIn(8, 32)
        preferences.edit().putInt("font_size", fontSize).apply()
    }

    fun chooseFont(value: TerminalFont) {
        preferences.edit().putString("font", value.name).apply()
        font = value
    }

    fun chooseMinimumContrast(value: Boolean) {
        preferences.edit().putBoolean("minimum_contrast", value).apply()
        minimumContrast = value
    }

    companion object {
        fun from(context: Context) = TerminalDisplaySettings(
            context.applicationContext.getSharedPreferences("terminal_display", Context.MODE_PRIVATE),
        )
    }
}

@Composable
fun rememberTerminalDisplaySettings(): TerminalDisplaySettings {
    val context = LocalContext.current.applicationContext
    return remember(context) { TerminalDisplaySettings.from(context) }
}
