package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*

internal sealed interface TaskNeed {
    val key: String
    data class Call(val entry: CallEntry) : TaskNeed { override val key = "call:${entry.key}" }
    data class Agent(val entry: AgentEntry) : TaskNeed { override val key = "agent:${entry.key.hostId}:${entry.key.address}:${entry.terminal.turn}:${entry.terminal.statusMs}:${entry.terminal.agentStatus}" }
}

internal fun taskNeeds(agents: List<AgentEntry>, calls: List<CallEntry>): List<TaskNeed> =
    calls.filter { it.call.answer == null && it.call.urgency != CallUrgency.RECORD }
        .sortedWith(compareByDescending<CallEntry> { it.call.urgency.ordinal }.thenByDescending { it.call.atMs })
        .map { TaskNeed.Call(it) } + agents.filter { isWaiting(it.terminal.agentStatus) }.map { TaskNeed.Agent(it) }

/** Captures identity/order on entry. Feed changes cannot shift the cursor past an unanswered item. */
internal class TasksNeedsQueue(val items: List<TaskNeed>, start: Int = 0, private val onResolved: (String) -> Unit = {}) {
    var position by mutableIntStateOf(start)
        private set
    val current get() = items.getOrNull(position)
    fun next() { position++ }
    fun resolve() { current?.let { onResolved(it.key) }; next() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksNeedsSheet(model: HostsModel, board: AgentBoard, queue: TasksNeedsQueue, onDismiss: () -> Unit) {
    val current = queue.current
    LaunchedEffect(current) { if (current == null) onDismiss() }
    if (current == null) return
    LifecycleStartEffect(model) {
        model.beginTaskNeeds()
        onStopOrDispose { model.endTaskNeeds() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("tasks-needs-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.tasks_needs_position, queue.items.size, queue.position + 1),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            key(current.key) {
                when (current) {
                    is TaskNeed.Call -> {
                        val entry = model.calls.calls.find { it.key == current.entry.key } ?: current.entry.copy(live = false)
                        val delivered = entry.call.answer?.state == CallAnswerState.DELIVERED
                        LaunchedEffect(delivered) { if (delivered) queue.resolve() }
                        ConsoleChatCall(entry, model.calls)
                        if (!entry.live) Text(stringResource(R.string.tasks_offline), Modifier.padding(16.dp))
                        if (entry.call.terminal != null) TextButton(onClick = {
                            onDismiss()
                            model.openPushedAgent(entry.host.id, entry.call.terminal!!, openChat = true)
                        }) { Text(stringResource(R.string.call_open_chat)) }
                    }
                    is TaskNeed.Agent -> TasksQuestion(model, board, current.entry, onAnswered = queue::resolve, onDismiss = onDismiss)
                }
                TextButton(onClick = queue::next, modifier = Modifier.testTag("tasks-needs-next")) {
                    Text(stringResource(R.string.tasks_needs_next))
                }
            }
        }
    }
}

@Composable
private fun TasksQuestion(model: HostsModel, board: AgentBoard, original: AgentEntry,
    onAnswered: () -> Unit, onDismiss: () -> Unit) {
    val entry = board.find(original.key) ?: original.copy(live = false)
    val link = board.links[entry.host.id]
    val connection = link?.connection
    val chat = remember(entry.key, entry.live, connection) { model.taskQuestion(board, entry.key) }
    val scope = rememberCoroutineScope()
    val sharedWithDetail = chat != null && model.isViewedChat(chat)
    DisposableEffect(chat, sharedWithDetail) {
        if (!sharedWithDetail) chat?.start(scope, paused = false)
        onDispose { if (chat != null && !sharedWithDetail && !model.isViewedChat(chat)) chat.stop() }
    }
    if (!sharedWithDetail) LifecycleStartEffect(chat) {
        chat?.resume()
        onStopOrDispose { if (chat != null && !model.isViewedChat(chat)) chat.pause() }
    }
    // Remember the question displayed for this queue item, even if the Host resolves it before
    // the answer RPC returns. Only an acknowledged phone answer automatically moves the cursor.
    var questionId by remember { mutableStateOf<String?>(null) }
    val question = chat?.snapshot?.entries?.firstOrNull {
        it.body is ChatEntryBody.Question && chat.questionMode(it.id) == ChatModel.QuestionMode.Live && chat.answers[it.id] != AnswerState.Sent
    }
    LaunchedEffect(question?.id) { if (question != null) questionId = question.id }
    val sent = questionId?.let { chat?.answers?.get(it) } == AnswerState.Sent
    LaunchedEffect(sent) { if (sent) onAnswered() }
    Text(displayName(entry.terminal), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
    Text(entry.host.name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    when {
        !entry.live -> Text(stringResource(R.string.tasks_offline), Modifier.padding(16.dp))
        question != null -> QuestionView(chat, question.id, question.body as ChatEntryBody.Question)
        chat?.snapshot == null -> ScreenLoading(stringResource(R.string.chat_loading), compact = true, onRetry = { chat?.retryConnection() })
        else -> {
            (entry.terminal.agentStatus as? AgentStatus.Error)?.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.tasks_needs_open), Modifier.padding(16.dp))
        }
    }
    TextButton(onClick = { onDismiss(); model.openAgent(board, entry, openChat = true) }, enabled = entry.live) {
        Text(stringResource(R.string.call_open_chat))
    }
}
