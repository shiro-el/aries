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

private fun taskQuestions(entry: AgentEntry, snapshot: ChatSnapshot?): List<ChatEntry> {
    val reason = (entry.terminal.agentStatus as? AgentStatus.Blocked)?.reason ?: return emptyList()
    if (reason != BlockedReason.QUESTION && reason != BlockedReason.PERMISSION) return emptyList()
    val entries = snapshot?.entries.orEmpty()
    return entries.drop(entries.indexOfLast { (it.body as? ChatEntryBody.User)?.queued == false } + 1)
        .filter { candidate -> (candidate.body as? ChatEntryBody.Question)?.let {
            (it.name == "permission") == (reason == BlockedReason.PERMISSION)
        } == true }
}

/** Only unresolved questions from the current user turn can request input. */
internal fun taskNeedQuestion(entry: AgentEntry, snapshot: ChatSnapshot?): ChatEntry? {
    val running = taskQuestions(entry, snapshot).filter { (it.body as ChatEntryBody.Question).status == ChatToolStatus.RUNNING }
    return if ((running.firstOrNull()?.body as? ChatEntryBody.Question)?.name == "request_user_input")
        running.first() else running.lastOrNull()
}

/** An empty transcript is still unknown; completed questions are positive completion evidence. */
private fun taskQuestionComplete(entry: AgentEntry, snapshot: ChatSnapshot?): Boolean =
    snapshot?.status == ChatStatus.LIVE && taskQuestions(entry, snapshot).isNotEmpty() && taskNeedQuestion(entry, snapshot) == null

internal fun taskNeeds(agents: List<AgentEntry>, calls: List<CallEntry>,
    transcript: (AgentEntry) -> ChatSnapshot? = { null },
    callWaiting: (CallEntry) -> Boolean = { it.needsAttention }): List<TaskNeed> =
    calls.filter(callWaiting)
        .sortedWith(compareByDescending<CallEntry> { it.call.urgency.ordinal }.thenByDescending { it.call.atMs })
        .map { TaskNeed.Call(it) } + agents.filter { isWaiting(it.terminal.agentStatus) }.mapNotNull { entry ->
            val snapshot = transcript(entry)
            val question = taskNeedQuestion(entry, snapshot)
            if (entry.live && taskQuestionComplete(entry, snapshot)) null
            else TaskNeed.Agent(entry, question?.id)
        }

private val TaskNeed.ownerKey: String get() = when (this) {
    is TaskNeed.Call -> key
    is TaskNeed.Agent -> "agent:${entry.key.hostId}:${entry.key.address}"
}

/** Keeps captured order and skipped items; new needs join the end of this visit. */
internal class TasksNeedsQueue(items: List<TaskNeed>, start: Int = 0) {
    var items by mutableStateOf(items)
        private set
    private val observed = items.mapTo(mutableSetOf()) { it.key }
    private val skipped = items.take(start).mapTo(mutableSetOf()) { it.ownerKey }
    var position by mutableIntStateOf(start)
        private set
    val current get() = items.getOrNull(position)
    fun append(needs: List<TaskNeed>) {
        val pending = items.drop(position).mapTo(mutableSetOf()) { it.ownerKey }
        val additions = needs.filter { observed.add(it.key) && it.ownerKey !in skipped && it.ownerKey !in pending }
        if (additions.isNotEmpty()) items = items + additions
    }
    fun next() {
        current?.let { skipped.add(it.ownerKey) }
        position++
    }
    fun resolve(key: String) {
        if (current?.key != key) return
        position++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksNeedsSheet(model: HostsModel, board: AgentBoard, queue: TasksNeedsQueue, onDismiss: () -> Unit) {
    // These observers outlive keyed cards and run before completion advances the cursor.
    val questionChats = remember(queue) { mutableStateListOf<ChatModel>() }
    CallReplyAnnouncements(model.calls, keys = queue.items.filterIsInstance<TaskNeed.Call>().map { it.entry.key }.toSet())
    questionChats.forEach { chat -> key(chat) { QuestionAnnouncements(chat, queueCompletion = true) } }
    val current = queue.current
    // Home appends arrivals in its SideEffect before this completion effect runs.
    LaunchedEffect(current) { if (queue.current == null) onDismiss() }
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
                        val caller = entry.call.terminal?.let { board.find(AgentKey(entry.host.id, it)) }
                        val resolved = entry.call.answer?.state == CallAnswerState.DELIVERED ||
                            entry.call.answer == null && !model.calls.isTaskWaiting(entry, caller)
                        LaunchedEffect(resolved) { if (resolved) queue.resolve(current.key) }
                        ConsoleChatCall(entry, model.calls)
                        if (entry.call.choices.isEmpty() && entry.call.answer == null) TextButton(onClick = {
                            model.calls.closeThreadCall(entry)
                            queue.resolve(current.key)
                        }) { Text(stringResource(R.string.ok_button)) }
                        if (!entry.live) Text(stringResource(R.string.tasks_call_checking), Modifier.padding(16.dp))
                        if (entry.call.terminal != null) TextButton(onClick = {
                            onDismiss()
                            model.openPushedAgent(entry.host.id, entry.call.terminal!!, openChat = true)
                        }) { Text(stringResource(R.string.tasks_open_task)) }
                    }
                    is TaskNeed.Agent -> TasksQuestion(model, board, current, onChat = { if (it !in questionChats) questionChats.add(it) },
                        onAnswered = { queue.resolve(current.key) })
                }
                TextButton(onClick = queue::next, modifier = Modifier.testTag("tasks-needs-next")) {
                    Text(stringResource(R.string.tasks_needs_next))
                }
            }
        }
    }
}

@Composable
private fun TasksQuestion(model: HostsModel, board: AgentBoard, need: TaskNeed.Agent,
    onChat: (ChatModel) -> Unit, onAnswered: () -> Unit) {
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
        if (chat == null) model.stopTaskQuestion() else { onChat(chat); model.startTaskQuestion(chat) }
    }
    // A key receipt is not confirmation. Keep the displayed question (and its
    // Unconfirmed recovery UI) until transcript completion or a live status change.
    val snapshot = if (entry.live) chat?.currentTranscript(entry.terminal) else chat?.snapshot
    val question = taskNeedQuestion(entry, snapshot)
    val confirmed = entry.live && (!isWaiting(entry.terminal.agentStatus) ||
        taskQuestionComplete(entry, snapshot))
    LaunchedEffect(confirmed) { if (confirmed) onAnswered() }
    Text(displayName(entry.terminal), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
    Text(entry.host.name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    when {
        (link?.state as? LinkState.Down)?.error is CoreException.KeyLocked -> {
            Text(stringResource(R.string.key_locked_body), Modifier.padding(16.dp))
            TextButton(onClick = link::retryNow) { Text(stringResource(R.string.key_unlock_button)) }
        }
        entry.host in board.loadingHosts -> ScreenLoading(stringResource(R.string.chat_loading), compact = true,
            onRetry = { link?.retryNow() })
        link?.state == LinkState.Reconnecting -> Text(stringResource(R.string.tasks_reconnecting), Modifier.padding(16.dp))
        !entry.live -> Text(stringResource(R.string.tasks_offline), Modifier.padding(16.dp))
        entry.terminal.agentStatus is AgentStatus.Error -> {
            Text(stringResource(R.string.tasks_needs_error), Modifier.padding(16.dp))
            statusMessage(entry.terminal.agentStatus)?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
        question != null && chat != null -> QuestionView(chat, question.id, question.body as ChatEntryBody.Question)
        snapshot == null -> ScreenLoading(stringResource(R.string.chat_loading), compact = true, onRetry = { chat?.retryConnection() })
        else -> {
            Text(stringResource(R.string.tasks_needs_open), Modifier.padding(16.dp))
        }
    }
    TextButton(onClick = { model.openTaskQuestion(board, entry) }, enabled = entry.live) {
        Text(stringResource(R.string.tasks_open_task))
    }
}
