package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import kotlin.math.roundToInt

private val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono),
    Font(R.font.jetbrains_mono_italic, style = FontStyle.Italic),
)

internal val TerminalFont.family: FontFamily
    get() = if (this == TerminalFont.JETBRAINS_MONO) JetBrainsMono else FontFamily.Monospace

@Composable
internal fun TerminalDisplaySection(settings: TerminalDisplaySettings, onShowLicense: (() -> Unit)? = null) {
    Text(stringResource(R.string.terminal_display_font_label), style = MaterialTheme.typography.bodySmall)
    ConsoleSegments(TerminalFont.entries, settings.font, settings::chooseFont) {
        stringResource(if (it == TerminalFont.SYSTEM) R.string.terminal_display_font_system else R.string.terminal_display_font_jetbrains)
    }
    Text(stringResource(R.string.terminal_display_size_label, settings.fontSize))
    ConsoleSlider(
        value = settings.fontSize.toFloat(),
        onValueChange = { settings.chooseFontSize(it.roundToInt()) },
        valueRange = 8f..32f,
        steps = 23,
        modifier = Modifier.fillMaxWidth().testTag("terminal-font-size"),
    )
    OptionToggle(
        R.string.terminal_display_contrast_label,
        settings.minimumContrast, settings::chooseMinimumContrast,
    )
    var showLicense by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { onShowLicense?.invoke() ?: run { showLicense = true } }) { Text(stringResource(R.string.terminal_display_license_button)) }
    if (showLicense) TerminalFontLicenseDialog { showLicense = false }
}

@Composable
internal fun TerminalFontLicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val license = remember(context) {
        context.assets.open("licenses/JetBrainsMono-OFL.txt").bufferedReader().use { it.readText() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_display_license_button)) },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text(license) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) } },
    )
}
