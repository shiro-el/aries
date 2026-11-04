package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Stable
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
    interceptChildLongPress: Boolean = true,
    /** Disclosure headers stay quiet while preserving their long-press text actions. */
    showIndication: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Freeze the selected text even while the Agent streams more of its reply.
    val sharedDialogs = LocalChatDialogs.current
    val dialogs = sharedDialogs ?: remember { ChatDialogState() }
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.chat_copy_all)
    val actionsLabel = stringResource(R.string.chat_text_actions)
    Box {
        val gestures = if (interceptChildLongPress && text.isNotEmpty()) {
            Modifier.childLongPress { menu = true }
        } else Modifier
        content(gestures.combinedClickable(
            interactionSource = null,
            indication = if (showIndication) LocalIndication.current else null,
            onClick = onClick,
            onLongClickLabel = actionsLabel,
            onLongClick = if (text.isEmpty()) null else { { menu = true } },
        ))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // Build the list only when requested; long transcripts stay cheap while streaming.
            if (menu) chatWebUrls(text).map { it.second }.distinct().forEach { url ->
                ActionMenuItem(
                    label = stringResource(R.string.chat_link_copy, url), icon = R.drawable.ic_link,
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(copyLabel, url))
                        menu = false
                    },
                )
                ActionMenuItem(
                    label = stringResource(R.string.chat_link_share, url), icon = R.drawable.ic_share_2,
                    onClick = {
                        menu = false
                        try {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }, null))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.chat_link_share_failed, Toast.LENGTH_SHORT).show()
                        } catch (_: SecurityException) {
                            Toast.makeText(context, R.string.chat_link_share_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
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
                onClick = { dialogs.selection = text; menu = false },
            )
        }
    }
    if (sharedDialogs == null) ChatDialogs(dialogs)
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

/** Screen-owned requests survive disposal or replacement of their originating lazy row. */
@Stable
internal class ChatDialogState {
    var selection by mutableStateOf<String?>(null)
    var image by mutableStateOf<ByteArray?>(null)
    var diagram by mutableStateOf<String?>(null)
    val isOpen: Boolean get() = selection != null || image != null || diagram != null
}

internal val LocalChatDialogs = staticCompositionLocalOf<ChatDialogState?> { null }
internal val LocalChatImageRevision = staticCompositionLocalOf { 0 }

@Composable
internal fun ChatDialogs(dialogs: ChatDialogState) {
    dialogs.selection?.let { selected ->
        Dialog(onDismissRequest = { dialogs.selection = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row {
                        TextButton(onClick = { dialogs.selection = null }) { Text(stringResource(R.string.back_button)) }
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
    dialogs.image?.let { ImageViewer(it, onDismiss = { dialogs.image = null }) }
    dialogs.diagram?.let { MermaidDialog(it, onDismiss = { dialogs.diagram = null }) }
}
