package io.github.shiroel.aries.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AnswerProblem
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
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
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
    /** Runs the calls that sign in to the Host (see [HostsModel]). */
    private val keyWork: CoroutineContext = EmptyCoroutineContext,
) {
    val inputHistory = InputHistoryModel(core, host.id, terminal.address)
    /** The Agent's Terminal, its Agent status kept live by [update]. */
    var terminal by mutableStateOf(terminal)
        private set
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

    /** Draft text replaces option picks for that part; never auto-sent. */
    var questionText by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    enum class QuestionMode { Live, Queued, History, ReadOnly }

    /**
     * Transcript Running means unresolved, not necessarily a live menu.
     * Only a caught-up Chat and question-blocked Terminal expose live keys.
     * Codex requests queue FIFO within the current user turn; Claude's newest
     * tool supersedes interrupted older ones. The Beacon rechecks before input.
     * With no live question, only the latest unresolved history may send a
     * normal message, through terminal.send's Blocked gate.
     */
    fun questionMode(id: String): QuestionMode {
        val snap = snapshot ?: return QuestionMode.ReadOnly
        if (snap.status != ChatStatus.LIVE) return QuestionMode.ReadOnly
        val entries = snap.entries
        val pending = entries.drop(entries.indexOfLast { (it.body as? ChatEntryBody.User)?.queued == false } + 1)
            .filter { (it.body as? ChatEntryBody.Question)?.status == ChatToolStatus.RUNNING }
        val blocked = terminal.agentStatus as? AgentStatus.Blocked
        if (blocked?.reason == BlockedReason.QUESTION) {
            val codex = pending.firstOrNull()?.body.let { (it as? ChatEntryBody.Question)?.name == "request_user_input" }
            val current = if (codex) pending.firstOrNull() else pending.lastOrNull()
            if (current?.id == id) return QuestionMode.Live
            return if (codex && pending.any { it.id == id }) QuestionMode.Queued else QuestionMode.ReadOnly
        }
        if (blocked != null || terminal.state != "live") return QuestionMode.ReadOnly
        val latestIndex = entries.indexOfLast { it.body is ChatEntryBody.Question }
        if (latestIndex < entries.indexOfLast { (it.body as? ChatEntryBody.User)?.queued == false }) return QuestionMode.ReadOnly
        val latest = entries.getOrNull(latestIndex)
        return if (latest?.id == id && (latest.body as ChatEntryBody.Question).status == ChatToolStatus.RUNNING)
            QuestionMode.History else QuestionMode.ReadOnly
    }

    fun writeAnswer(id: String, question: ChatEntryBody.Question, part: Int, text: String) {
        if (answers[id] == AnswerState.Sending || answers[id] == AnswerState.Sent) return
        val current = questionText[id] ?: List(question.questions.size) { "" }
        questionText = questionText + (id to current.mapIndexed { i, value -> if (i == part) text else value })
        val selected = picks[id] ?: List(question.questions.size) { emptySet() }
        picks = picks + (id to selected.mapIndexed { i, value -> if (i == part) emptySet() else value })
    }

    /** What the user is typing into the input. */
    var input by mutableStateOf(TextFieldValue(""))
    // Keep the String seam for attachments, dictation and existing callers.
    var draft: String
        get() = input.text
        set(value) { input = TextFieldValue(value, TextRange(value.length)) }
    var sending by mutableStateOf<Sending?>(null)
        private set
    /** The Blocked Agent refused the last message; cleared once it is no longer Blocked. */
    var refused by mutableStateOf(false)
        private set
    /** Why the last message could not be sent, other than a refusal. */
    var sendError by mutableStateOf<String?>(null)
        private set

    var pendingShare by mutableStateOf<SharedContent?>(null)
    var uploading by mutableStateOf(false)
        private set
    var attachmentError by mutableStateOf<Message?>(null)
        private set
    private var uploadJob: Job? = null

    fun attachmentFailed(error: Exception) {
        attachmentError = if (error is AttachmentTooLarge) Message(R.string.attachments_too_large)
            else Message(R.string.attachments_failed, listOf(error.message ?: ""))
    }

    /** Saves bytes and appends paths to the current draft; never calls sendMessage. */
    fun attach(context: Context, content: SharedContent, cleanup: () -> Unit = {}) {
        val scope = scope ?: return
        if (uploading || sending != null) return
        val connection = link?.connection ?: run { attachmentFailed(CoreException.Closed()); return }
        val resize = AttachmentSettings.from(context).resizeImages
        attachmentError = null
        uploading = true
        fun append(value: String) {
            draft += (if (draft.isEmpty() || draft.last().isWhitespace()) "" else " ") + value
        }
        content.text?.let(::append)
        uploadJob = scope.launch {
            try {
                for (uri in content.uris) {
                    val attachment = withContext(Dispatchers.IO) { prepareAttachment(context, uri, resize) }
                    val path = connection.uploadAttachment(terminal.address, attachment.name, attachment.bytes)
                    append(shellQuote(path))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attachmentFailed(e)
            } finally {
                uploading = false
                cleanup()
            }
        }
    }

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

    val canSend: Boolean get() = link != null && sending == null && !uploading && pendingShare == null && draft.isNotBlank()

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
                    SendOutcome.Sent -> {
                        inputHistory.remember(text)
                        if (draft == text) draft = ""
                    }
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
                withContext(keyWork) { core.watchChat(host.id, terminal.address, null) }
            } catch (e: CoreException) {
                // Still show what the cache holds (ticket 44).
                val cached = try { core.cachedChat(host.id, terminal.address) } catch (e: CoreException) { null }
                snapshot = ChatSnapshot(cached?.entries.orEmpty(), ChatStatus.NO_BEACON, cached?.cursor)
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

    /**
     * The user tapped option [option] of part [part] of question [id]. A
     * lone single-choice question is answered right away, like the Agent's
     * own TUI; otherwise the pick is toggled until [send].
     */
    fun pick(id: String, question: ChatEntryBody.Question, part: Int, option: Int) {
        if (answers[id] == AnswerState.Sending || answers[id] == AnswerState.Sent) return
        val mode = questionMode(id)
        if (mode != QuestionMode.Live && mode != QuestionMode.History) return
        val text = questionText[id] ?: List(question.questions.size) { "" }
        questionText = questionText + (id to text.mapIndexed { i, value -> if (i == part) "" else value })
        if (question.questions.size == 1 && !question.questions[0].multi) {
            answer(id, question, listOf(listOf(option)))
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
        return question.questions.indices.all { part ->
            picks[id]?.getOrNull(part)?.isNotEmpty() == true || questionText[id]?.getOrNull(part)?.isNotBlank() == true
        }
    }

    fun send(id: String, question: ChatEntryBody.Question) {
        if (!complete(id, question)) return
        answer(id, question, question.questions.indices.map { picks[id]?.getOrNull(it).orEmpty().sorted() })
    }

    private fun answer(id: String, question: ChatEntryBody.Question, chosen: List<List<Int>>) {
        val scope = scope ?: return
        if (answers[id] == AnswerState.Sending || answers[id] == AnswerState.Sent) return
        val mode = questionMode(id)
        if (mode != QuestionMode.Live && mode != QuestionMode.History) return
        val texts = question.questions.indices.map { questionText[id]?.getOrNull(it)?.takeIf(String::isNotBlank) }
        // Single-choice taps submit directly, without going through the
        // draft selection map. Keep their submitted choice visible too.
        picks = picks + (id to chosen.map { it.toSet() })
        answers = answers + (id to AnswerState.Sending)
        scope.launch {
            val state = try {
                withContext(keyWork) {
                    if (mode == QuestionMode.History) {
                        val connection = link?.connection ?: throw CoreException.Closed()
                        val parts = question.questions.mapIndexed { i, part ->
                            texts[i] ?: chosen[i].joinToString(", ") { part.options[it].label }
                        }
                        val text = if (parts.size == 1) parts[0] else parts.mapIndexed { i, part -> "${question.questions[i].text}: $part" }.joinToString("\n")
                        when (val outcome = connection.sendMessage(terminal.address, text, HeldListener())) {
                            SendOutcome.Sent -> {}
                            is SendOutcome.Blocked -> {
                                terminal = terminal.copy(agentStatus = AgentStatus.Blocked(outcome.reason, outcome.message))
                                throw CoreException.AnswerRefused(AnswerProblem.GONE, "")
                            }
                        }
                    } else if (texts.any { it != null }) {
                        core.answerQuestionWithText(host.id, terminal.address, id, chosen.map { p -> p.map { it.toUInt() } }, texts)
                    } else {
                        core.answerQuestion(host.id, terminal.address, id, chosen.map { p -> p.map { it.toUInt() } })
                    }
                }
                AnswerState.Sent
            } catch (e: CoreException) {
                AnswerState.Failed(answerProblem(e))
            }
            answers = answers + (id to state)
        }
    }

    /** The network changed: replace the Chat's line now; it resumes after its cursor. */
    fun reconnect() {
        watch?.reconnect()
    }

    /** The app is back in front: retry a waiting reconnect now. */
    fun retryNow() {
        watch?.retryNow()
    }

    fun stop() {
        uploadJob?.cancel()
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
fun ChatScreen(
    chat: ChatModel,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onView: (AgentView) -> Unit = {},
    calls: CallsModel? = null,
) {
    val readAloud = io.github.shiroel.aries.speech.rememberReadAloud(chat)
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(readAloud.notice) {
        readAloud.notice?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    val snapshot = chat.snapshot
    val entries = snapshot?.entries.orEmpty()
    val markdown by rememberChatMarkdown(entries)
    var searchOpen by remember(chat) { mutableStateOf(false) }
    var query by remember(chat) { mutableStateOf("") }
    var selected by remember(chat) { mutableStateOf<ChatSearchMatch?>(null) }
    val matches by rememberChatSearch(entries, markdown, query)
    val selectedIndex = selectedChatSearchIndex(matches, selected)
    val active = matches.getOrNull(selectedIndex)
    fun closeSearch() { searchOpen = false; query = ""; selected = null }
    BackHandler(enabled = searchOpen, onBack = ::closeSearch)
    val agentCalls = calls?.let { model ->
        model.calls.filter {
            it.host.id == chat.host.id && it.call.terminal == chat.terminal.address &&
                (it.answerable || it.key in model.replying || it.key in model.replyNotes)
        }.sortedBy { it.call.atMs }
    }.orEmpty()
    val scroll = rememberChatScrollState(chat, entries, agentCalls.map { "call:${it.key}" }, markdown, followEnabled = !searchOpen)
    LaunchedEffect(active) {
        if (active != null) {
            val body = entries.find { it.id == active.field.entryId }?.body
            if (body is ChatEntryBody.Tool && active.field.entryId !in chat.expanded) chat.toggle(active.field.entryId)
            scroll.following = false
            scroll.list.scrollToItem(active.field.itemIndex)
        }
    }
    val searchRanges = remember(matches) { matches.flatMap { it.ranges }.groupBy { it.field.entryId to it.field.key } }
    val searchDecoration = remember(searchRanges, active) { ChatSearchDecoration(searchRanges, active) }
    CompositionLocalProvider(LocalChatSearch provides searchDecoration) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
            AgentScreenHeader(chat.terminal, chat.host.name, onBack, AgentView.CHAT, onView,
                link = chat.link, onTerminalUpdate = { chat.update(listOf(it)) }, onSearch = { searchOpen = true })
            ChatSearchBar(searchOpen, query, selectedIndex + 1, matches.size,
                onQuery = { query = it; selected = null },
                onMove = { selected = navigateChatSearch(matches, active, it) },
                onClose = ::closeSearch,
            )
            statusText(snapshot?.status ?: ChatStatus.CONNECTING)?.let { ChatNotice(it) }
            if (snapshot != null && entries.isEmpty() && snapshot.status == ChatStatus.LIVE) {
                Text(stringResource(R.string.chat_empty))
            }
            io.github.shiroel.aries.speech.ReadAloudPlayback(readAloud, showInactiveNotice = false)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().testTag("chat-transcript"), state = scroll.list) {
                    entries.forEach { entry ->
                        val body = entry.body
                        val source = body.markdownSource()
                        item(key = "transcript:${entry.id}") {
                            CompositionLocalProvider(LocalChatSearchEntry provides entry.id) {
                                if (body is ChatEntryBody.Question) {
                                    QuestionView(chat, entry.id, body)
                                } else {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (body is ChatEntryBody.Agent) 0.dp else 12.dp)) {
                                        ChatEntryView(entry, displayName(chat.terminal), expanded = entry.id in chat.expanded, chat::image, readAloud) { chat.toggle(entry.id) }
                                    }
                                    if (source == null) HorizontalDivider()
                                }
                            }
                        }
                        if (source != null) {
                            // Keep the last complete document visible until the background parse swaps it.
                            chatMarkdownItems(entry.id, markdown[entry.id], thinking = body is ChatEntryBody.Thinking)
                            item(key = "transcript:${entry.id}:footer") {
                                if (body is ChatEntryBody.User && body.images.isNotEmpty()) {
                                    Column(Modifier.padding(horizontal = 16.dp)) {
                                        body.images.forEach { ChatImageView(it, chat::image) }
                                    }
                                }
                                if (body is ChatEntryBody.Agent) body.metadata?.let { metadata ->
                                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        ChatResponseMetadataView(metadata)
                                    }
                                }
                                HorizontalDivider(Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    items(agentCalls, key = { "call:${it.key}" }) { entry ->
                        if (calls != null) ConsoleChatCall(entry, calls)
                    }
                }
                if (!searchOpen && !scroll.following && (entries.isNotEmpty() || agentCalls.isNotEmpty())) {
                    TextButton(
                        onClick = scroll::latest,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        Text(if (scroll.unreadCount == 0) stringResource(R.string.chat_latest_button)
                            else stringResource(R.string.chat_latest_count_button, scroll.unreadCount))
                    }
                }
            }
            (chat.terminal.agentStatus as? AgentStatus.Blocked)?.let {
                // A question the Chat shows open is answered with its own buttons;
                // the Terminal notice is for what the Chat cannot answer.
                val answerable = it.reason == BlockedReason.QUESTION && entries.any { e ->
                    (e.body as? ChatEntryBody.Question)?.status == ChatToolStatus.RUNNING
                }
                if (!answerable || chat.refused) BlockedNotice(it, refused = chat.refused, onOpenTerminal)
            }
            when (chat.sending) {
                ChatModel.Sending.Sending -> ChatNotice(stringResource(R.string.chat_sending))
                ChatModel.Sending.Held -> ChatNotice(stringResource(R.string.chat_send_held))
                null -> {}
            }
            chat.sendError?.let {
                Text(stringResource(R.string.chat_send_failed, it), color = MaterialTheme.colorScheme.error)
            }
            if (chat.uploading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                ChatNotice(stringResource(R.string.attachments_uploading))
            }
            chat.attachmentError?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
            if (chat.link != null) {
                ChatCatalogControls(chat)
                MessageInput(chat)
            }
        }
    }
}

@Composable
private fun MessageInput(chat: ChatModel) {
    val inputLabel = stringResource(R.string.chat_input_label)
    val inputStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    HorizontalDivider()
    io.github.shiroel.aries.dictation.DictationInput(
        names = listOf(chat.host.name, displayName(chat.terminal), chat.terminal.cwd.orEmpty().trimEnd('/').substringAfterLast('/')),
        lastResponse = chat.snapshot?.entries?.asReversed()?.firstNotNullOfOrNull { (it.body as? ChatEntryBody.Agent)?.text }.orEmpty(),
        onTranscript = { text -> chat.draft = if (chat.draft.isBlank()) text else chat.draft + " " + text },
        enabled = chat.sending == null,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        BasicTextField(
            value = chat.input,
            onValueChange = { chat.input = it },
            modifier = Modifier.weight(1f)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = inputLabel },
            textStyle = inputStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            maxLines = 6,
            enabled = chat.sending == null,
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    if (chat.draft.isEmpty()) {
                        Text(inputLabel, style = inputStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    field()
                }
            },
        )
        TextButton(
            onClick = chat::send,
            enabled = chat.canSend,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = RectangleShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(stringResource(R.string.chat_send_button), style = inputStyle)
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
    val color = statusColor(status)
    ConsoleChatCallSurface {
        if (refused) {
            Text(stringResource(R.string.chat_send_refused), color = color, style = MaterialTheme.typography.labelLarge)
        }
        Text(stringResource(why), color = color)
        status.message?.let {
            Text(it, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        ConsoleButton(onClick = onOpenTerminal) { Text(stringResource(R.string.terminal_open_button)) }
    }
}

@Composable
private fun statusText(status: ChatStatus): String? = when (status) {
    ChatStatus.LIVE -> null
    ChatStatus.CONNECTING -> stringResource(R.string.chat_status_connecting)
    ChatStatus.WAITING -> null
    ChatStatus.NO_AGENT -> null
    ChatStatus.ENDED -> null
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
    agentName: String,
    expanded: Boolean,
    image: (String) -> ByteArray?,
    readAloud: io.github.shiroel.aries.speech.ReadAloudState,
    onToggle: () -> Unit,
) {
    when (val b = entry.body) {
        is ChatEntryBody.User -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(if (b.queued) R.string.chat_user_queued else R.string.chat_you),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is ChatEntryBody.Agent -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(agentName, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            io.github.shiroel.aries.speech.ReadAloudButton(readAloud, entry.id, b.text)
        }
        is ChatEntryBody.Thinking -> Column {
            Text(
                stringResource(R.string.chat_thinking),
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            ChatTextActions(b.detail) { SearchableChatText(b.detail, "error", it, color = MaterialTheme.colorScheme.onErrorContainer) }
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
private fun ToolView(
    tool: ChatEntryBody.Tool,
    expanded: Boolean,
    image: (String) -> ByteArray?,
    onToggle: () -> Unit,
) {
    val open = LocalOpenRemoteFile.current
    // Relative tool paths refer to the cwd recorded with the call, which may
    // differ from the Terminal's current foreground directory.
    val cwd = tool.details?.cwd
    val path = tool.details?.path ?: chatFileLocation(tool.title)?.path
    val toolOpen: ((FileLocation) -> Unit)? = open?.let { navigate ->
        { target ->
            val embedded = if (target.path == path) tool.images.firstOrNull { it.available } else null
            val preview = embedded?.let { picture -> image(picture.id)?.let { FilePreview(it, picture.mediaType) } }
            navigate(target.copy(
                path = if (!target.path.startsWith('/') && !cwd.isNullOrBlank()) cwd.trimEnd('/') + "/" + target.path else target.path,
                preview = preview,
            ))
        }
    }
    CompositionLocalProvider(LocalOpenRemoteFile provides toolOpen) {
        ToolContent(tool, expanded, image, onToggle)
    }
}

@Composable
private fun ToolContent(
    tool: ChatEntryBody.Tool,
    expanded: Boolean,
    image: (String) -> ByteArray?,
    onToggle: () -> Unit,
) {
    val label = stringResource(R.string.chat_tool_expand_label, tool.name)
    val failed = tool.status == ChatToolStatus.FAILED
    val open = LocalOpenRemoteFile.current
    val color = MaterialTheme.colorScheme.primary
    val title = remember(tool.title, tool.details?.path, open, color) {
        annotateChatFileLinks(chatToolPathText(tool.title, tool.details?.path), open, color)
    }
    ChatTextActions(tool.output?.takeIf { it.isNotEmpty() } ?: tool.input, onClick = onToggle) { actions ->
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .then(actions)
                    .semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchableChatText(tool.name, "tool-name", style = MaterialTheme.typography.labelLarge)
                SearchableChatText(
                    title, "tool-title",
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
                tool.details?.let { ChatToolDetailsView(it) }
                if (tool.input.isNotBlank()) ChatRawInput(tool.input)
                tool.output?.let {
                    Text(stringResource(R.string.chat_tool_output), style = MaterialTheme.typography.labelMedium)
                    ChatTextActions(it, onClick = onToggle) { modifier ->
                        SearchableChatText(
                            it, "tool-output", modifier,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
