package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.ChatWatch
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One Terminal's Agent Chat, as the core reads it through the Host's Beacon.
 * The rendering knows nothing about the Agent: every Agent's Chat arrives as
 * the same [ChatEntry] list.
 */
class ChatModel(
    private val core: AriesCoreInterface,
    val host: Host,
    val terminal: TerminalInfo,
) {
    var snapshot by mutableStateOf<ChatSnapshot?>(null)
        private set
    /** Ids of tool entries the user expanded. */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set

    private var watch: ChatWatch? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            val w = try {
                core.watchChat(host.id, terminal.address, null)
            } catch (e: CoreException) {
                snapshot = ChatSnapshot(emptyList(), ChatStatus.NO_BEACON, null)
                return@launch
            }
            watch = w
            try {
                while (true) snapshot = w.next()
            } catch (e: CoreException) {
                // Final status reached or stopped: keep the last snapshot.
            }
        }
    }

    /** Bytes of a [ChatImage]; blocking but cheap (the core holds them in memory). */
    fun image(id: String): ByteArray? = watch?.image(id)

    fun toggle(id: String) {
        expanded = if (id in expanded) expanded - id else expanded + id
    }

    fun stop() {
        job?.cancel()
        watch?.stop()
        watch = null
    }
}

@Composable
fun ChatScreen(chat: ChatModel, onBack: () -> Unit) {
    val snapshot = chat.snapshot
    val entries = snapshot?.entries.orEmpty()
    val list = rememberLazyListState()
    // Follow the conversation: keep the newest entry in view.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) list.scrollToItem(entries.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
            Text(
                stringResource(R.string.chat_title, displayName(chat.terminal)),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        statusText(snapshot?.status ?: ChatStatus.CONNECTING)?.let { ChatNotice(it) }
        if (snapshot != null && entries.isEmpty() && snapshot.status == ChatStatus.LIVE) {
            Text(stringResource(R.string.chat_empty))
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { entry ->
                ChatEntryView(entry, expanded = entry.id in chat.expanded, chat::image) { chat.toggle(entry.id) }
            }
        }
    }
}

@Composable
private fun statusText(status: ChatStatus): String? = when (status) {
    ChatStatus.LIVE -> null
    ChatStatus.CONNECTING -> stringResource(R.string.chat_status_connecting)
    ChatStatus.WAITING -> stringResource(R.string.chat_status_waiting)
    ChatStatus.NO_AGENT -> stringResource(R.string.chat_status_no_agent)
    ChatStatus.ENDED -> stringResource(R.string.chat_status_ended)
    ChatStatus.RECONNECTING -> stringResource(R.string.chat_status_reconnecting)
    ChatStatus.NO_TERMINAL -> stringResource(R.string.chat_status_no_terminal)
    ChatStatus.NO_BEACON -> stringResource(R.string.chat_status_no_beacon)
    ChatStatus.NO_CORE -> stringResource(R.string.chat_status_no_core)
    ChatStatus.INCOMPATIBLE -> stringResource(R.string.chat_status_incompatible)
}

@Composable
private fun ChatNotice(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ChatEntryView(
    entry: ChatEntry,
    expanded: Boolean,
    image: (String) -> ByteArray?,
    onToggle: () -> Unit,
) {
    when (val b = entry.body) {
        is ChatEntryBody.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.chat_you),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (b.text.isNotEmpty()) Text(b.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
                b.images.forEach { ChatImageView(it, image) }
            }
        }
        is ChatEntryBody.Agent -> Text(b.text)
        is ChatEntryBody.Thinking -> Column {
            Text(
                stringResource(R.string.chat_thinking),
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            b.text?.let {
                Text(it, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is ChatEntryBody.Tool -> ToolView(b, expanded, image, onToggle)
        is ChatEntryBody.AgentError -> Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
        ) {
            Text(
                stringResource(R.string.chat_agent_error),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(b.detail, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        ChatEntryBody.Unsupported -> Text(
            stringResource(R.string.chat_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolView(
    tool: ChatEntryBody.Tool,
    expanded: Boolean,
    image: (String) -> ByteArray?,
    onToggle: () -> Unit,
) {
    val label = stringResource(R.string.chat_tool_expand_label, tool.name)
    val failed = tool.status == ChatToolStatus.FAILED
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = label }
            .padding(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tool.name, style = MaterialTheme.typography.labelLarge)
            Text(
                tool.title,
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                fontFamily = FontFamily.Monospace,
            )
            when (tool.status) {
                ChatToolStatus.RUNNING -> Text(
                    stringResource(R.string.chat_tool_running),
                    style = MaterialTheme.typography.labelSmall,
                )
                ChatToolStatus.FAILED -> Text(
                    stringResource(R.string.chat_tool_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                ChatToolStatus.DONE -> {}
            }
        }
        tool.diff?.let { DiffView(it, expanded) }
        tool.images.forEach { ChatImageView(it, image) }
        if (expanded) {
            Text(stringResource(R.string.chat_tool_input), style = MaterialTheme.typography.labelMedium)
            Text(tool.input, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            tool.output?.let {
                Text(stringResource(R.string.chat_tool_output), style = MaterialTheme.typography.labelMedium)
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
