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
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*

internal sealed interface TaskNeed {
    val key: String
    data class Call(val entry: CallEntry) : TaskNeed { override val key = "call:${entry.key}" }
    data class Agent(val entry: AgentEntry, val questionId: String? = null) : TaskNeed {
        override val key = "agent:${entry.key.hostId}:${entry.key.address}:${entry.terminal.turn}:${entry.terminal.statusMs}:${entry.terminal.agentStatus}:$questionId"
    }
}

/** Current-turn question identity, including confirmed completion while status catches up. */
internal fun taskNeedQuestion(entry: AgentEntry, snapshot: ChatSnapshot?): ChatEntry? {
    val reason = (entry.terminal.agentStatus as? AgentStatus.Blocked)?.reason ?: return null
    if (reason != BlockedReason.QUESTION && reason != BlockedReason.PERMISSION) return null
    val entries = snapshot?.entries.orEmpty()
    val questions = entries.drop(entries.indexOfLast { (it.body as? ChatEntryBody.User)?.queued == false } + 1)
        .filter { candidate -> (candidate.body as? ChatEntryBody.Question)?.let {
            (it.name == "permission") == (reason == BlockedReason.PERMISSION)
        } == true }
    val running = questions.filter { (it.body as ChatEntryBody.Question).status == ChatToolStatus.RUNNING }
    return if ((running.firstOrNull()?.body as? ChatEntryBody.Question)?.name == "request_user_input")
        running.first() else running.lastOrNull() ?: questions.lastOrNull()
}

internal fun taskNeeds(agents: List<AgentEntry>, calls: List<CallEntry>,
    transcript: (AgentKey) -> ChatSnapshot? = { null }): List<TaskNeed> =
    calls.filter { it.needsAttention }
        .sortedWith(compareByDescending<CallEntry> { it.call.urgency.ordinal }.thenByDescending { it.call.atMs })
        .map { TaskNeed.Call(it) } + agents.filter { isWaiting(it.terminal.agentStatus) }.mapNotNull { entry ->
            val snapshot = transcript(entry.key)
            val question = taskNeedQuestion(entry, snapshot)
            if (entry.live && snapshot?.status == ChatStatus.LIVE &&
                (question?.body as? ChatEntryBody.Question)?.status == ChatToolStatus.DONE) null
            else TaskNeed.Agent(entry, question?.id)
        }

/** Captures identity/order on entry. Feed changes cannot shift the cursor past an unanswered item. */
internal class TasksNeedsQueue(val items: List<TaskNeed>, start: Int = 0) {
    var position by mutableIntStateOf(start)
        private set
    val questionIds = mutableStateMapOf<String, String>()
    val current get() = items.getOrNull(position)
    fun next() { position++ }
    fun resolve(key: String) {
        if (current?.key != key) return
        next()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksNeedsSheet(model: HostsModel, board: AgentBoard, queue: TasksNeedsQueue, onDismiss: () -> Unit) {
    val current = queue.current
    LaunchedEffect(current) { if (current == null) onDismiss() }
    if (current == null) return
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("tasks-needs-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val name = when (current) {
                is TaskNeed.Agent -> displayName(board.find(current.entry.key)?.terminal ?: current.entry.terminal)
                is TaskNeed.Call -> model.callCallerName(current.entry) ?: current.entry.call.title
            }
            QueueHeading(listOf(stringResource(R.string.tasks_needs_position, queue.items.size, queue.position + 1), name).joinToString(" · "),
                current.key, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            key(current.key) {
                when (current) {
                    is TaskNeed.Call -> {
                        LaunchedEffect(current.key) { model.stopTaskQuestion() }
                        val entry = model.calls.calls.find { it.key == current.entry.key } ?: current.entry.copy(live = false)
                        val delivered = entry.call.answer?.state == CallAnswerState.DELIVERED
                        LaunchedEffect(delivered) { if (delivered) queue.resolve(current.key) }
                        ConsoleChatCall(entry, model.calls, announce = true)
                        if (!entry.live) Text(stringResource(R.string.tasks_offline), Modifier.padding(16.dp))
                        if (entry.call.terminal != null) TextButton(onClick = {
                            onDismiss()
                            model.openPushedAgent(entry.host.id, entry.call.terminal!!, openChat = true)
                        }) { Text(stringResource(R.string.tasks_open_task)) }
                    }
                    is TaskNeed.Agent -> TasksQuestion(model, board, current, queue, onAnswered = { queue.resolve(current.key) })
                }
                TextButton(onClick = queue::next, modifier = Modifier.testTag("tasks-needs-next")) {
                    Text(stringResource(R.string.tasks_needs_next))
                }
            }
        }
    }
}

@Composable
private fun TasksQuestion(model: HostsModel, board: AgentBoard, need: TaskNeed.Agent, queue: TasksNeedsQueue,
    onAnswered: () -> Unit) {
    val original = need.entry
    val entry = board.find(original.key) ?: original.copy(live = false)
    val link = board.links[entry.host.id]
    val connection = link?.connection
    var retainedChat by remember(entry.key) { mutableStateOf(model.taskQuestion(board, entry.key)) }
    // A sheet opened from last-known state can acquire its owner once live.
    // List freshness and replacement SSH connections do not replace that owner.
    LaunchedEffect(entry.key, entry.live, connection) {
        if (retainedChat == null) retainedChat = model.taskQuestion(board, entry.key)
    }
    val chat = retainedChat
    LaunchedEffect(chat) {
        if (chat == null) model.stopTaskQuestion() else model.startTaskQuestion(chat)
    }
    // A key receipt is not confirmation. Keep the displayed question (and its
    // Unconfirmed recovery UI) until transcript completion or a live status change.
    val questionId = queue.questionIds[need.key] ?: need.questionId
    val snapshot = chat?.snapshot
    val question = snapshot?.entries?.find { it.id == questionId && it.body is ChatEntryBody.Question }
        ?: taskNeedQuestion(entry, snapshot)
    LaunchedEffect(question?.id) { if (question != null) queue.questionIds[need.key] = question.id }
    val confirmed = entry.live && (!isWaiting(entry.terminal.agentStatus) ||
        snapshot?.status == ChatStatus.LIVE && (question?.body as? ChatEntryBody.Question)?.status == ChatToolStatus.DONE)
    LaunchedEffect(confirmed) { if (confirmed) onAnswered() }
    Text(displayName(entry.terminal), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
    Text(entry.host.name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    when {
        (link?.state as? LinkState.Down)?.error is CoreException.KeyLocked -> {
            Text(stringResource(R.string.key_locked_body), Modifier.padding(16.dp))
            TextButton(onClick = link::retryNow) { Text(stringResource(R.string.key_unlock_button)) }
        }
        link?.state == LinkState.Reconnecting -> Text(stringResource(R.string.tasks_reconnecting), Modifier.padding(16.dp))
        !entry.live -> Text(stringResource(R.string.tasks_offline), Modifier.padding(16.dp))
        entry.terminal.agentStatus is AgentStatus.Error -> {
            Text(stringResource(R.string.tasks_needs_error), Modifier.padding(16.dp))
            statusMessage(entry.terminal.agentStatus)?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
        question != null && chat != null -> QuestionView(chat, question.id, question.body as ChatEntryBody.Question, announce = true)
        chat?.snapshot == null -> ScreenLoading(stringResource(R.string.chat_loading), compact = true, onRetry = { chat?.retryConnection() })
        else -> {
            Text(stringResource(R.string.tasks_needs_open), Modifier.padding(16.dp))
        }
    }
    TextButton(onClick = { model.openTaskQuestion(board, entry) }, enabled = entry.live) {
        Text(stringResource(R.string.tasks_open_task))
    }
}
