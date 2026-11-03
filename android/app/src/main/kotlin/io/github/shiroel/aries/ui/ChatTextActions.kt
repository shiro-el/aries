package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenuItem
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
    content: @Composable (Modifier) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Freeze the selected text even while the Agent streams more of its reply.
    var selection by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.chat_copy_all)
    val actionsLabel = stringResource(R.string.chat_text_actions)
    Box {
        content(Modifier.combinedClickable(
            onClick = onClick,
            onLongClickLabel = actionsLabel,
            onLongClick = if (text.isEmpty()) null else { { menu = true } },
        ))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(copyLabel) },
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(copyLabel, text))
                    menu = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_select_text)) },
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
                    Text(stringResource(R.string.chat_select_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(selected, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
