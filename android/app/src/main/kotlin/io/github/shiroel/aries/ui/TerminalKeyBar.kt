package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.TerminalKey
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fixed seven-column rows, independent of whether the IME is showing. */
@Composable
internal fun TerminalKeyBar(viewer: TerminalViewer) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth()) {
            BarKey(R.string.key_esc) { viewer.press(TerminalKey.Escape) }
            BarKey(R.string.key_slash) { viewer.press(TerminalKey.Char("/")) }
            BarKey(R.string.key_dash) { viewer.press(TerminalKey.Char("-")) }
            BarKey(R.string.key_home, repeating = true) { viewer.press(TerminalKey.Home) }
            BarKey(R.string.key_up, repeating = true, fontSize = 20.sp) { viewer.press(TerminalKey.Up) }
            BarKey(R.string.key_end, repeating = true) { viewer.press(TerminalKey.End) }
            BarKey(R.string.key_page_up, repeating = true) { viewer.press(TerminalKey.PageUp) }
        }
        Row(Modifier.fillMaxWidth()) {
            BarKey(R.string.key_tab) { viewer.press(TerminalKey.Tab) }
            BarKey(R.string.key_ctrl, held = viewer.ctrl, onClick = viewer::toggleCtrl)
            BarKey(R.string.key_alt, held = viewer.alt, onClick = viewer::toggleAlt)
            BarKey(R.string.key_left, repeating = true, fontSize = 20.sp) { viewer.press(TerminalKey.Left) }
            BarKey(R.string.key_down, repeating = true, fontSize = 20.sp) { viewer.press(TerminalKey.Down) }
            BarKey(R.string.key_right, repeating = true, fontSize = 20.sp) { viewer.press(TerminalKey.Right) }
            BarKey(R.string.key_page_down, repeating = true) { viewer.press(TerminalKey.PageDown) }
        }
    }
}

@Composable
internal fun TerminalKeyBarToggle(viewer: TerminalViewer) {
    ActionIcon(
        if (viewer.keyBarVisible) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up,
        if (viewer.keyBarVisible) R.string.terminal_keys_hide_button else R.string.terminal_keys_show_button,
        viewer::toggleKeyBar,
    )
}

@Composable
private fun RowScope.BarKey(
    label: Int,
    held: Boolean? = null,
    repeating: Boolean = false,
    fontSize: TextUnit = 12.sp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val modifierState = stringResource(if (held == true) R.string.terminal_keys_on else R.string.terminal_keys_off)
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val send by rememberUpdatedState(onClick)
    val repeatDelay = LocalViewConfiguration.current.longPressTimeoutMillis
    Box(
        Modifier.weight(1f).height(48.dp).padding(2.dp)
            .background(when {
                held == true -> colors.primary
                pressed -> colors.secondaryContainer
                else -> colors.surface
            })
            .border(1.dp, colors.outlineVariant)
            .semantics {
                if (held != null) {
                    selected = held
                    stateDescription = modifierState
                }
            }
            .pointerInput(repeating, repeatDelay) {
                if (repeating) coroutineScope {
                    val scope = this
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val repeat = scope.launch {
                            delay(repeatDelay)
                            while (true) {
                                send()
                                delay(60)
                            }
                        }
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            // Cancel in the pointer event, without waiting for recomposition.
                            repeat.cancel()
                        }
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                // Consume a hold so releasing it does not add a final tap.
                onLongClick = if (repeating) ({}) else null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(label),
            color = if (held == true) colors.onPrimary else colors.onSurface,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontSize = fontSize),
            maxLines = 1,
        )
    }
}
