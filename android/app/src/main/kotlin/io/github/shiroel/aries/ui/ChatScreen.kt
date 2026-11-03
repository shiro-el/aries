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
import androidx.compose.material3.OutlinedButton
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
import io.github.shiroel.aries.core.AnswerProblem
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

    /** Options the user picked per open question: entry id -> picks per part. */
    var picks by mutableStateOf<Map<String, List<Set<Int>>>>(emptyMap())
        private set
    /** Where each answer the user sent stands, by entry id. */
    var answers by mutableStateOf<Map<String, AnswerState>>(emptyMap())
        private set

    private var watch: ChatWatch? = null
    private var job: Job? = null
    private var scope: CoroutineScope? = null

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

    /**
     * The user tapped option [option] of part [part] of question [id]. A
     * lone single-choice question is answered right away, like the Agent's
     * own TUI; otherwise the pick is toggled until [send].
     */
    fun pick(id: String, question: ChatEntryBody.Question, part: Int, option: Int) {
        if (question.questions.size == 1 && !question.questions[0].multi) {
            answer(id, listOf(listOf(option)))
            return
        }
        val current = picks[id] ?: List(question.questions.size) { emptySet() }
        val multi = question.questions[part].multi
        val chosen = current[part]
        val next = when {
            !multi -> setOf(option)
            option in chosen -> chosen - option
            else -> chosen + option
        }
        picks = picks + (id to current.mapIndexed { i, p -> if (i == part) next else p })
    }

    /** Every part of question [id] has a pick. */
    fun complete(id: String, question: ChatEntryBody.Question): Boolean {
        val p = picks[id] ?: return false
        return p.size == question.questions.size && p.all { it.isNotEmpty() }
    }

    fun send(id: String, question: ChatEntryBody.Question) {
        if (!complete(id, question)) return
        answer(id, picks.getValue(id).map { it.sorted() })
    }

    private fun answer(id: String, chosen: List<List<Int>>) {
        val scope = scope ?: return
        if (answers[id] == AnswerState.Sending) return
        answers = answers + (id to AnswerState.Sending)
        scope.launch {
            val state = try {
                core.answerQuestion(host.id, terminal.address, id, chosen.map { p -> p.map { it.toUInt() } })
                AnswerState.Sent
            } catch (e: CoreException) {
                AnswerState.Failed(answerProblem(e))
            }
            answers = answers + (id to state)
        }
    }

    fun stop() {
        job?.cancel()
        watch?.stop()
        watch = null
    }
}

sealed interface AnswerState {
    data object Sending : AnswerState
    /** The keys reached the Agent; the Chat shows the result when it has it. */
    data object Sent : AnswerState
    data class Failed(val why: Message) : AnswerState
}

/** Why an answer was not sent, for the question card. */
fun answerProblem(e: CoreException): Message = when (e) {
    is CoreException.AnswerRefused -> when (e.reason) {
        AnswerProblem.ANSWERED -> Message(R.string.chat_answer_answered)
        AnswerProblem.GONE -> Message(R.string.chat_answer_gone)
        AnswerProblem.INVALID -> Message(R.string.chat_answer_invalid)
        AnswerProblem.UNSUPPORTED -> Message(R.string.chat_answer_unsupported)
        AnswerProblem.NO_BEACON -> Message(R.string.chat_status_no_beacon)
        AnswerProblem.NO_CORE -> Message(R.string.chat_answer_no_core)
        AnswerProblem.FAILED -> Message(R.string.chat_answer_failed, listOf(e.detail))
    }
    else -> Message(R.string.chat_answer_failed, listOf(e.message ?: ""))
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
                val body = entry.body
                if (body is ChatEntryBody.Question) {
                    QuestionView(chat, entry.id, body)
                } else {
                    ChatEntryView(entry, expanded = entry.id in chat.expanded) { chat.toggle(entry.id) }
                }
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
        // Rendered by QuestionView, which needs the model.
        is ChatEntryBody.Question -> {}
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

@Composable
private fun QuestionView(chat: ChatModel, id: String, q: ChatEntryBody.Question) {
    val open = q.status == ChatToolStatus.RUNNING
    val state = chat.answers[id]
    val lone = q.questions.size == 1 && !q.questions[0].multi
    val chosen = chat.picks[id]
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.chat_question_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        q.questions.forEachIndexed { part, question ->
            question.header?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            Text(question.text, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (open && question.multi) {
                Text(stringResource(R.string.chat_question_multi_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (open) {
                question.options.forEachIndexed { i, option ->
                    val label = stringResource(R.string.chat_question_option_label, option.label)
                    val selected = chosen?.getOrNull(part)?.contains(i) == true
                    val enabled = state != AnswerState.Sending && state != AnswerState.Sent
                    val onClick = { chat.pick(id, q, part, i) }
                    val modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                    val content: @Composable () -> Unit = {
                        Column(Modifier.fillMaxWidth()) {
                            Text(option.label)
                            option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (selected) {
                        Button(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
                    } else {
                        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
                    }
                }
            }
        }
        if (open && !lone && state != AnswerState.Sent) {
            Button(
                onClick = { chat.send(id, q) },
                enabled = state != AnswerState.Sending,
            ) { Text(stringResource(R.string.chat_question_send)) }
        }
        if (open) {
            when (state) {
                AnswerState.Sending -> Text(stringResource(R.string.chat_question_sending), style = MaterialTheme.typography.bodySmall)
                AnswerState.Sent -> Text(stringResource(R.string.chat_question_sent), style = MaterialTheme.typography.bodySmall)
                is AnswerState.Failed -> Text(
                    stringResource(state.why.res, *state.why.args.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> {}
            }
        } else {
            q.output?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
