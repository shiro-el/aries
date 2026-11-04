package io.github.shiroel.aries.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Device-local reading preference shared by full files and their Git diffs. */
internal class FileViewerSettings(private val preferences: SharedPreferences) {
    var wrap by mutableStateOf(preferences.getBoolean("wrap", false))
        private set

    fun chooseWrap(value: Boolean) {
        preferences.edit().putBoolean("wrap", value).apply()
        wrap = value
    }

    companion object {
        fun from(context: Context) = FileViewerSettings(
            context.applicationContext.getSharedPreferences("file_viewer", Context.MODE_PRIVATE),
        )
    }
}

@Composable
internal fun rememberFileViewerSettings(): FileViewerSettings {
    val context = LocalContext.current.applicationContext
    return remember(context) { FileViewerSettings.from(context) }
}
