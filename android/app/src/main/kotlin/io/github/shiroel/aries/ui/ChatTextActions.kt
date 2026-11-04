package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R

/**
 * Adds local text actions without coupling them to the text renderer. [text] is
 * the complete source, including whitespace, rather than a rendered preview.
 * A separate, fully composed surface lets selection span a long lazy-list item.
 */
@Composable
internal fun ChatTextActions(
    text: String,
    onClick: () -> Unit = {},
    /** Observe long presses before clickable spans consume their pointer input. */
    interceptChildLongPress: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Freeze the selected text even while the Agent streams more of its reply.
    var selection by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.chat_copy_all)
    val actionsLabel = stringResource(R.string.chat_text_actions)
    Box {
        val gestures = if (interceptChildLongPress && text.isNotEmpty()) {
            Modifier.childLongPress { menu = true }
        } else Modifier
        content(gestures.combinedClickable(
            onClick = onClick,
            onLongClickLabel = actionsLabel,
            onLongClick = if (text.isEmpty()) null else { { menu = true } },
        ))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            ActionMenuItem(
                label = copyLabel, icon = R.drawable.ic_copy,
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(copyLabel, text))
                    menu = false
                },
            )
            ActionMenuItem(
                label = stringResource(R.string.chat_select_text), icon = R.drawable.ic_text_select,
                onClick = { selection = text; menu = false },
            )
        }
    }
    selection?.let { selected ->
        Dialog(onDismissRequest = { selection = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row {
                        TextButton(onClick = { selection = null }) { Text(stringResource(R.string.back_button)) }
                        Text(stringResource(R.string.chat_select_text), Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider()
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(selected, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Leave taps and scrolling to child links / the list; consume only a held press. */
private fun Modifier.childLongPress(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var tracking = true
            while (tracking) {
                val changes = awaitPointerEvent(PointerEventPass.Initial).changes
                val pointer = changes.singleOrNull()
                tracking = pointer != null && pointer.id == down.id && pointer.pressed &&
                    (pointer.position - down.position).getDistance() <= viewConfiguration.touchSlop
            }
            true
        }
        if (cancelled == null) {
            onLongPress()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
