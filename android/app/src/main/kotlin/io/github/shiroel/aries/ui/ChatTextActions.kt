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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.ChatResponseMetadata

/**
 * Adds local text actions without coupling them to the text renderer. [text] is
 * the complete source, including whitespace, rather than a rendered preview.
 * A separate, fully composed surface lets selection span a long lazy-list item.
 */
@Composable
internal fun ChatTextActions(
    text: String,
    onClick: () -> Unit = {},
    onClickLabel: String? = null,
    /** Observe long presses before clickable spans consume their pointer input. */
    interceptChildLongPress: Boolean = true,
    /** Disclosure headers stay quiet while preserving their long-press text actions. */
    showIndication: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    // Freeze the selected text even while the Agent streams more of its reply.
    val sharedDialogs = LocalChatDialogs.current
    val dialogs = sharedDialogs ?: remember { ChatDialogState() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val metadata = LocalChatResponseMetadata.current
    val entryId = LocalChatSearchEntry.current
    val openMenu = rememberUpdatedState { dialogs.textMenu = ChatTextMenu(text, bounds, metadata, entryId) }
    val actionsLabel = stringResource(R.string.chat_text_actions)
    Box {
        val gestures = if (interceptChildLongPress && text.isNotEmpty()) {
            Modifier.childLongPress { openMenu.value() }
        } else Modifier
        content(gestures.onGloballyPositioned { bounds = it.boundsInWindow() }.combinedClickable(
            interactionSource = null,
            indication = if (showIndication) LocalIndication.current else null,
            onClick = onClick,
            onClickLabel = onClickLabel,
            onLongClickLabel = actionsLabel,
            onLongClick = if (text.isEmpty()) null else { { openMenu.value() } },
        ))
    }
    if (sharedDialogs == null) ChatDialogs(dialogs)
}

/** The popup is owned by the screen; its frozen anchor can outlive a recycled lazy row. */
@Composable
private fun ChatTextMenuView(menu: ChatTextMenu, dialogs: ChatDialogState, chat: ChatModel?) {
    val text = menu.text
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.chat_copy_all)
    val actionsLabel = stringResource(R.string.chat_text_actions)
    val position = remember(menu) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) menu.bounds.left.toInt()
                    else menu.bounds.right.toInt() - popupContentSize.width
                val below = menu.bounds.bottom.toInt()
                val y = if (below + popupContentSize.height <= windowSize.height) below
                    else menu.bounds.top.toInt() - popupContentSize.height
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = { dialogs.textMenu = null },
        properties = PopupProperties(focusable = true)) {
        Surface(shape = MenuDefaults.shape, color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation, shadowElevation = MenuDefaults.ShadowElevation) {
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 240.dp, max = 320.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)
                .semantics { paneTitle = actionsLabel }) {
                // Build the list only when requested; long transcripts stay cheap while streaming.
                menu.metadata?.let { metadata ->
                    ActionMenuItem(label = stringResource(R.string.chat_response_details_button), icon = R.drawable.ic_circle_dot,
                        onClick = { dialogs.responseMetadata = metadata; dialogs.textMenu = null })
                }
                chatWebUrls(text).map { it.second }.distinct().forEach { url ->
                    ActionMenuItem(
                        label = stringResource(R.string.chat_link_copy, url), icon = R.drawable.ic_link,
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText(copyLabel, url))
                            dialogs.textMenu = null
                        },
                    )
                    ActionMenuItem(
                        label = stringResource(R.string.chat_link_share, url), icon = R.drawable.ic_share_2,
                        onClick = {
                            dialogs.textMenu = null
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
                chat?.rewindTarget(menu.entryId)?.let { target ->
                    ActionMenuItem(
                        label = stringResource(R.string.chat_rewind_here), icon = R.drawable.ic_history,
                        enabled = chat.canRewind,
                        detail = if (chat.terminal.agentStatus == io.github.shiroel.aries.core.AgentStatus.Working)
                            stringResource(R.string.chat_rewind_stop_first) else null,
                        onClick = { dialogs.rewind = target; dialogs.textMenu = null },
                    )
                }
                ActionMenuItem(
                    label = copyLabel, icon = R.drawable.ic_copy,
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(copyLabel, text))
                        dialogs.textMenu = null
                    },
                )
                ActionMenuItem(
                    label = stringResource(R.string.chat_select_text), icon = R.drawable.ic_text_select,
                    onClick = { dialogs.selection = text; dialogs.textMenu = null },
                )
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

/** Screen-owned requests survive disposal or replacement of their originating lazy row. */
@Stable
internal class ChatDialogState {
    var showUsage by mutableStateOf(false)
    var textMenu by mutableStateOf<ChatTextMenu?>(null)
    var tasksRequest by mutableStateOf<TasksRequest?>(null)
    var rewind by mutableStateOf<ChatRewindTarget?>(null)
    var selection by mutableStateOf<String?>(null)
    var image by mutableStateOf<ByteArray?>(null)
    var diagram by mutableStateOf<String?>(null)
    var responseMetadata by mutableStateOf<ChatResponseMetadata?>(null)
    fun dismiss() {
        showUsage = false
        textMenu = null
        tasksRequest = null
        selection = null
        rewind = null
        image = null
        diagram = null
        responseMetadata = null
    }
    val isOpen: Boolean get() = textMenu != null || rewind != null || tasksRequest != null || selection != null || image != null || diagram != null || responseMetadata != null
}

internal data class ChatTextMenu(val text: String, val bounds: Rect, val metadata: ChatResponseMetadata? = null, val entryId: String? = null)

internal val LocalChatDialogs = staticCompositionLocalOf<ChatDialogState?> { null }
internal val LocalChatImageRevision = staticCompositionLocalOf { 0 }

@Composable
internal fun ChatDialogs(dialogs: ChatDialogState, chat: ChatModel? = null, onRewind: (String) -> Unit = {}) {
    ChatRewindConfirmation(dialogs, chat, onRewind)
    dialogs.responseMetadata?.let { metadata ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialogs.responseMetadata = null },
            title = { Text(stringResource(R.string.chat_response_details_button)) },
            text = { ChatResponseMetadataDetails(metadata, multiline = true) },
            confirmButton = { TextButton(onClick = { dialogs.responseMetadata = null }) { Text(stringResource(R.string.ok_button)) } },
        )
    }
    dialogs.textMenu?.let { ChatTextMenuView(it, dialogs, chat) }
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
