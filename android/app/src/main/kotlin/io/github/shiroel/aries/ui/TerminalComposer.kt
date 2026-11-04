package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.TerminalKey

/** Standard Android editing and OS paste, followed by one core paste operation. */
@Composable
internal fun TerminalComposer(viewer: TerminalViewer) {
    val style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    if (!viewer.composerOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TerminalInput(viewer, Modifier.weight(1f))
            if (viewer.typing) TerminalKeyBarToggle(viewer)
            ActionIcon(R.drawable.ic_square_pen, R.string.terminal_compose_button, viewer::openComposer)
        }
        return
    }
    val hint = stringResource(R.string.terminal_composer_hint)
    val active = viewer.canInput
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        viewer.inputUnavailableReason?.let { reason ->
            Text(stringResource(reason), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        viewer.inputHistory?.let { history ->
            InputHistoryButton(history) { viewer.draft = it }
        }
        BasicTextField(
            value = viewer.draft,
            onValueChange = { viewer.draft = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .testTag("terminal-composer")
                .semantics { contentDescription = hint },
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            minLines = 2,
            maxLines = 6,
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (viewer.draft.isEmpty()) {
                        Text(hint, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    field()
                }
            },
        )
        if (viewer.pasteSending || viewer.pasteUncertain) {
            AnnouncedText(
                stringResource(if (viewer.pasteSending) R.string.terminal_paste_sending else R.string.terminal_paste_uncertain),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (viewer.typing) TerminalKeyBarToggle(viewer)
            ActionIcon(R.drawable.ic_keyboard, R.string.terminal_direct_input_button, viewer::closeComposer)
            Spacer(Modifier.weight(1f))
            ActionIcon(R.drawable.ic_corner_down_left, R.string.terminal_enter_button,
                onClick = { viewer.startTyping(); viewer.press(TerminalKey.Enter) }, enabled = active)
            ActionIcon(R.drawable.ic_send, R.string.terminal_composer_send_button, viewer::pasteDraft, enabled = viewer.canPasteDraft)
        }
    }
}
