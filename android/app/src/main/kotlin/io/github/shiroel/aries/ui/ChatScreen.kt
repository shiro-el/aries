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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.ChatWatch
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.SendListener
import io.github.shiroel.aries.core.SendOutcome
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
    terminal: TerminalInfo,
    /** The open Host's line: messages go through its live connection. */
    val link: HostLink? = null,
) {
    /** The Agent's Terminal, its Agent status kept live by [update]. */
    var terminal by mutableStateOf(terminal)
        private set
    var snapshot by mutableStateOf<ChatSnapshot?>(null)
        private set
    /** Ids of tool entries the user expanded. */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set

    /** What the user is typing into the input. */
    var draft by mutableStateOf("")
    var sending by mutableStateOf<Sending?>(null)
        private set
    /** The Blocked Agent refused the last message; cleared once it is no longer Blocked. */
    var refused by mutableStateOf(false)
        private set
    /** Why the last message could not be sent, other than a refusal. */
    var sendError by mutableStateOf<String?>(null)
        private set

    enum class Sending { Sending, Held }

    private var watch: ChatWatch? = null
    private var job: Job? = null
    private var scope: CoroutineScope? = null

    /** The Host's Terminals changed: follow this Agent's status. */
    fun update(terminals: List<TerminalInfo>) {
        val now = terminals.find { it.address == terminal.address } ?: return
        terminal = now
        if (now.agentStatus !is AgentStatus.Blocked) refused = false
    }

    val canSend: Boolean get() = link != null && sending == null && draft.isNotBlank()

    /**
     * Types [draft] into the Agent's Terminal. It stays in the input until it
     * went in, so a refused or failed message can be sent again. Never resent
     * on its own after a dropped line: it may have gone in already.
     */
    fun send() {
        val scope = scope ?: return
        if (!canSend) return
        val conn = link?.connection
        if (conn == null) {
            sendError = CoreException.Closed().let { it.message ?: it.toString() }
            return
        }
        val text = draft
        sending = Sending.Sending
        refused = false
        sendError = null
        scope.launch {
            try {
                when (val outcome = conn.sendMessage(terminal.address, text, HeldListener())) {
                    SendOutcome.Sent -> if (draft == text) draft = ""
                    is SendOutcome.Blocked -> {
                        refused = true
                        terminal = terminal.copy(
                            agentStatus = AgentStatus.Blocked(outcome.reason, outcome.message),
                            needsAttention = true,
                        )
                    }
                }
            } catch (e: CoreException) {
                sendError = e.message ?: e.toString()
            } finally {
                sending = null
            }
        }
    }

    private inner class HeldListener : SendListener {
        override fun onHeld() {
            if (sending == Sending.Sending) sending = Sending.Held
        }
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
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
fun ChatScreen(chat: ChatModel, onBack: () -> Unit, onOpenTerminal: () -> Unit = {}) {
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
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { entry ->
                ChatEntryView(entry, expanded = entry.id in chat.expanded) { chat.toggle(entry.id) }
            }
        }
        (chat.terminal.agentStatus as? AgentStatus.Blocked)?.let {
            BlockedNotice(it, refused = chat.refused, onOpenTerminal)
        }
        when (chat.sending) {
            ChatModel.Sending.Sending -> ChatNotice(stringResource(R.string.chat_sending))
            ChatModel.Sending.Held -> ChatNotice(stringResource(R.string.chat_send_held))
            null -> {}
        }
        chat.sendError?.let {
            Text(stringResource(R.string.chat_send_failed, it), color = MaterialTheme.colorScheme.error)
        }
        if (chat.link != null) MessageInput(chat)
    }
}

@Composable
private fun MessageInput(chat: ChatModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = chat.draft,
            onValueChange = { chat.draft = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.chat_input_label)) },
            enabled = chat.sending == null,
        )
        Button(onClick = chat::send, enabled = chat.canSend) {
            Text(stringResource(R.string.chat_send_button))
        }
    }
}

/**
 * The Agent is Blocked: say why and send the user to the Terminal. A
 * permission request is only shown here, never answered: approving happens
 * in the Terminal.
 */
@Composable
private fun BlockedNotice(status: AgentStatus.Blocked, refused: Boolean, onOpenTerminal: () -> Unit) {
    val why = when (status.reason) {
        BlockedReason.PERMISSION -> R.string.chat_blocked_permission
        BlockedReason.QUESTION -> R.string.chat_blocked_question
        BlockedReason.AUTH -> R.string.chat_blocked_auth
        null -> R.string.chat_blocked_other
    }
    val color = MaterialTheme.colorScheme.onErrorContainer
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (refused) {
            Text(stringResource(R.string.chat_send_refused), color = color, style = MaterialTheme.typography.labelLarge)
        }
        Text(stringResource(why), color = color)
        status.message?.let {
            Text(it, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpenTerminal) { Text(stringResource(R.string.terminal_open_button)) }
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
private fun ChatEntryView(entry: ChatEntry, expanded: Boolean, onToggle: () -> Unit) {
    when (val b = entry.body) {
        is ChatEntryBody.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp),
            ) {
                Text(
                    stringResource(R.string.chat_you),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(b.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
        is ChatEntryBody.Tool -> ToolView(b, expanded, onToggle)
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
private fun ToolView(tool: ChatEntryBody.Tool, expanded: Boolean, onToggle: () -> Unit) {
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
