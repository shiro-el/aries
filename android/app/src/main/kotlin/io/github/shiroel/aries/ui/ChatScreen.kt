package io.github.shiroel.aries.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.PERMISSION_TOOL
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AnswerProblem
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatImage
import io.github.shiroel.aries.core.ChatResponseMetadata
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.ChatWatch
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.SendListener
import io.github.shiroel.aries.core.SendOutcome
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
    link: HostLink? = null,
    /** Runs the calls that sign in to the Host (see [HostsModel]). */
    private val keyWork: CoroutineContext = EmptyCoroutineContext,
) {
    internal val files = RemoteFilesPanel()
    internal val preview = LocalhostPreview()
    internal var selectedSearchMatch by mutableStateOf<ChatSearchMatch?>(null)
    var link by mutableStateOf(link)
        private set

    /** Reopening reuses drafts and pending operations; only the live SSH line changes. */
    fun bind(link: HostLink, terminal: TerminalInfo) {
        inputHistory.open = false
        if (this.link !== link) clearNotices()
        this.link = link
        update(listOf(terminal))
    }

    val inputHistory = InputHistoryModel(core, host.id, terminal.address)
    /** The Agent's Terminal, its Agent status kept live by [update]. */
    var terminal by mutableStateOf(terminal)
        private set
    var snapshot by mutableStateOf<ChatSnapshot?>(null)
        private set
    /** Typed failures survive initial watch creation and later SSH reconnects. */
    var connectionError by mutableStateOf<CoreException?>(null)
        private set
    /** Tool entry IDs and response:<entry ID> disclosures the user expanded. */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Disclosure state survives switching between following and history renderers. */
    var rawInputsExpanded by mutableStateOf<Set<String>>(emptySet())
        private set

    /** One read-aloud control per turn, even when tools split the Agent's text. */
    val readAloudTurns: Map<String, String> by derivedStateOf {
        buildMap {
            var firstId: String? = null
            snapshot?.entries.orEmpty().forEach { entry ->
                when (val body = entry.body) {
                    is ChatEntryBody.User -> firstId = null
                    is ChatEntryBody.Agent -> if (body.text.isNotBlank()) {
                        val id = firstId ?: entry.id.also { firstId = it }
                        put(id, get(id)?.let { "$it\n\n${body.text}" } ?: body.text)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Options the user picked per open question: entry id -> picks per part. */
    var picks by mutableStateOf<Map<String, List<Set<Int>>>>(emptyMap())
        private set
    /** Where each answer the user sent stands, by entry id. */
    var answers by mutableStateOf<Map<String, AnswerState>>(emptyMap())
        private set

    /** Draft text replaces option picks for that part; never auto-sent. */
    var questionText by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    enum class QuestionMode { Live, LivePending, Reconnecting, Queued, History, ReadOnly }

    /**
     * Transcript Running means unresolved, not necessarily a live menu.
     * Only a caught-up Chat and a matching Blocked reason expose live keys.
     * Permission projections never become historical message replies.
     * Codex requests queue FIFO within the current user turn; Claude's newest
     * tool supersedes interrupted older ones. The Beacon rechecks before input.
     * With no live question, only the latest unresolved history may send a
     * normal message, through terminal.send's Blocked gate.
     */
    fun questionMode(id: String): QuestionMode {
        if (inputUnavailable) return QuestionMode.ReadOnly
        val snap = snapshot ?: return QuestionMode.ReadOnly
        val entries = snap.entries
        val pending = entries.drop(entries.indexOfLast { (it.body as? ChatEntryBody.User)?.queued == false } + 1)
            .filter { (it.body as? ChatEntryBody.Question)?.status == ChatToolStatus.RUNNING }
        if (snap.status != ChatStatus.LIVE || (link != null && link?.state != LinkState.Up)) {
            return if (pending.any { it.id == id } &&
                (snap.status in setOf(ChatStatus.CONNECTING, ChatStatus.RECONNECTING, ChatStatus.NO_CORE) ||
                    (snap.status == ChatStatus.LIVE && link?.state != LinkState.Up)))
                QuestionMode.Reconnecting else QuestionMode.ReadOnly
        }
        val blocked = terminal.agentStatus as? AgentStatus.Blocked
        val permission = (entries.find { it.id == id }?.body as? ChatEntryBody.Question)?.name == PERMISSION_TOOL
        if (permission) {
            if (terminal.agentStatus == AgentStatus.Working &&
                pending.lastOrNull { (it.body as? ChatEntryBody.Question)?.name == PERMISSION_TOOL }?.id == id)
                return QuestionMode.LivePending
            return if (terminal.state == "live" && blocked?.reason == BlockedReason.PERMISSION &&
                pending.lastOrNull { (it.body as? ChatEntryBody.Question)?.name == PERMISSION_TOOL }?.id == id)
                QuestionMode.Live else QuestionMode.ReadOnly
        }
        if (blocked?.reason == BlockedReason.QUESTION) {
            val questions = pending.filter { (it.body as? ChatEntryBody.Question)?.name != PERMISSION_TOOL }
            val codex = questions.firstOrNull()?.body.let { (it as? ChatEntryBody.Question)?.name == "request_user_input" }
            val current = if (codex) questions.firstOrNull() else questions.lastOrNull()
            if (current?.id == id) return QuestionMode.Live
            return if (codex && questions.any { it.id == id }) QuestionMode.Queued else QuestionMode.ReadOnly
        }
        if (blocked != null || terminal.state != "live") return QuestionMode.ReadOnly
        if (terminal.agentStatus == AgentStatus.Working) {
            val questions = pending.filter { (it.body as? ChatEntryBody.Question)?.name != PERMISSION_TOOL }
            val codex = (questions.firstOrNull()?.body as? ChatEntryBody.Question)?.name == "request_user_input"
            val current = if (codex) questions.firstOrNull() else questions.lastOrNull()
            return when {
                current?.id == id -> QuestionMode.LivePending
                codex && questions.any { it.id == id } -> QuestionMode.Queued
                else -> QuestionMode.ReadOnly
            }
        }
        val latestIndex = entries.indexOfLast { it.body is ChatEntryBody.Question }
        if (latestIndex < entries.indexOfLast { (it.body as? ChatEntryBody.User)?.queued == false }) return QuestionMode.ReadOnly
        val latest = entries.getOrNull(latestIndex)
        return if (latest?.id == id && (latest.body as ChatEntryBody.Question).status == ChatToolStatus.RUNNING)
            QuestionMode.History else QuestionMode.ReadOnly
    }

    fun writeAnswer(id: String, question: ChatEntryBody.Question, part: Int, text: String) {
        if (answers[id] == AnswerState.Sending || answers[id] == AnswerState.Held || answers[id] == AnswerState.Sent || answers[id] == AnswerState.Unconfirmed) return
        val current = questionText[id] ?: List(question.questions.size) { "" }
        questionText = questionText + (id to current.mapIndexed { i, value -> if (i == part) text else value })
        val selected = picks[id] ?: List(question.questions.size) { emptySet() }
        picks = picks + (id to selected.mapIndexed { i, value -> if (i == part) emptySet() else value })
        clearNotices()
        saveOutbox()
    }

    /** What the user is typing into the input. */
    private var editableInput by mutableStateOf(TextFieldValue(""))
    var input: TextFieldValue
        get() = editableInput
        set(value) {
            val changed = value.text != editableInput.text
            editableInput = value
            if (changed) {
                clearNotices()
                saveOutbox()
            }
        }

    fun appendDraft(text: String, separator: String = "\n\n") {
        if (text.isEmpty()) return
        val current = input
        val suffix = (if (current.text.isEmpty()) "" else separator) + text
        // External additions must not discard the IME's active composition.
        input = current.copy(text = current.text + suffix)
    }
    // External history/restoration/file edits use this String seam. The IME owns
    // text, selection and composition until it commits; even an equal-text
    // TextFieldValue replacement can end dictation and move the cursor.
    var draft: String
        get() = input.text
        set(value) {
            if (value == input.text || input.composition != null) return
            input = TextFieldValue(value, TextRange(value.length))
        }
    var sending by mutableStateOf<Sending?>(null)
        private set
    var sent by mutableStateOf(false)
        private set
    /** The Blocked Agent refused the last message; cleared once it is no longer Blocked. */
    var refused by mutableStateOf(false)
        private set
    /** Why the last message could not be sent, other than a refusal. */
    var sendError by mutableStateOf<String?>(null)
        private set

    var interrupting by mutableStateOf(false)
        private set
    var interruptNotice by mutableStateOf<Message?>(null)
        private set
    val canInterrupt: Boolean get() = terminalAvailable && terminal.agentStatus == AgentStatus.Working &&
        link?.connection != null && !interrupting && sending == null

    /** One confirmed attempt, with no reconnect queue or automatic retry. */
    fun interrupt() {
        val scope = scope ?: return
        if (!canInterrupt) return
        val connection = link?.connection ?: return
        val address = terminal.address
        interrupting = true
        interruptNotice = null
        val revision = noticeRevision
        scope.launch {
            try {
                val notice = interruptMessage(connection.interruptAgent(address))
                if (noticeRevision == revision) interruptNotice = notice
            } catch (e: CoreException) {
                if (noticeRevision == revision) interruptNotice = Message(R.string.chat_interrupt_failed, listOf(e.message ?: e.toString()))
            } finally {
                interrupting = false
                drainQueue()
            }
        }
    }

    var pendingShare by mutableStateOf<SharedContent?>(null)
    private var draftAttachments by mutableStateOf<List<DraftAttachment>>(emptyList())
    var attachments: List<DraftAttachment>
        get() = draftAttachments
        private set(value) {
            draftAttachments = value
            saveOutbox()
        }

    fun removeAttachment(attachment: DraftAttachment) {
        clearNotices()
        attachments = attachments - attachment
    }
    var uploading by mutableStateOf(false)
        private set
    var attachmentError by mutableStateOf<Message?>(null)
        private set
    private var uploadJob: Job? = null

    fun attachmentFailed(error: Exception) {
        attachmentError = Message(when {
            error is AttachmentTooLarge || (error is CoreException.Rpc && error.kind == "attachment_too_large") -> R.string.attachments_too_large
            error is CoreException.Closed || error is CoreException.Unreachable -> R.string.attachments_connection_failed
            error is java.io.IOException || error is SecurityException -> R.string.attachments_read_failed
            else -> R.string.attachments_failed
        })
    }

    /** Saves bytes and adds attachment chips to the current draft; never calls sendMessage. */
    fun attach(context: Context, content: SharedContent, cleanup: () -> Unit = {}): Boolean {
        val scope = scope ?: run { cleanup(); return false }
        if (inputUnavailable || uploading) { cleanup(); return false }
        val link = link ?: run { attachmentFailed(CoreException.Closed()); cleanup(); return false }
        val resize = AttachmentSettings.from(context).resizeImages
        clearNotices()
        uploading = true
        fun append(value: String) {
            appendDraft(value, separator = if (draft.isEmpty() || draft.last().isWhitespace()) "" else " ")
        }
        content.text?.let(::append)
        uploadJob = scope.launch {
            try {
                for (uri in content.uris) {
                    val attachment = withContext(Dispatchers.IO) { prepareAttachment(context, uri, resize) }
                    // The picker/camera may stop the Activity and release its Host connection.
                    // Wait for its replacement after reading the URI; never retry a put itself.
                    val connection = link.awaitConnection()
                    if (inputUnavailable) break
                    val path = try {
                        connection.uploadAttachment(terminal.address, attachment.name, attachment.bytes)
                    } catch (e: CoreException.Closed) {
                        link.lost(connection)
                        throw e
                    }
                    attachments = attachments + DraftAttachment(path, attachment.name, attachment.bytes.size,
                        attachment.bytes.takeIf { remoteMediaKind(attachment.name) == RemoteMediaKind.IMAGE })
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
        return true
    }

    var queuedMessages by mutableStateOf<List<QueuedChatMessage>>(emptyList())
        private set
    private var sendJob: Job? = null
    private var connectionJob: Job? = null
    private var waitingConnection: HostConnection? = null
    private var stopped = false
    private var terminalRevision = 0L
    private var recoveryLoaded = false
    private var outboxEdits = 0
    private var recoveryFailed by mutableStateOf(false)
    val terminalComposer = TerminalComposerState { saveOutbox() }

    private var noticeRevision = 0L
    private fun clearNotices() {
        noticeRevision++
        sendError = null
        refused = false
        interruptNotice = null
        attachmentError = null
    }

    /** Callers commit once after moving text and attachments together between draft and queue. */
    private inline fun changeOutbox(change: () -> Unit) {
        outboxEdits++
        try { change() } finally { outboxEdits-- }
    }

    private fun saveOutbox(): Boolean {
        if (!recoveryLoaded || outboxEdits > 0) return true
        if (recoveryFailed) return false
        return try {
            val state = ChatOutboxState(draft, attachments, queuedMessages,
                terminalComposer.draft, terminalComposer.open, questionText, picks,
                answers.mapValues { (_, answer) -> when (answer) {
                    AnswerState.Sending, AnswerState.Held -> "sending"
                    AnswerState.Sent, AnswerState.Unconfirmed -> "sent"
                    is AnswerState.Failed -> "failed"
                } })
            core.saveChatOutbox(host.id, terminal.address, state.encode())
            true
        } catch (e: CoreException) {
            sendError = e.message
            false
        }
    }

    private fun restoreOutbox() {
        if (recoveryLoaded) return
        try {
            val saved = core.chatOutbox(host.id, terminal.address)?.let(::decodeChatOutbox) ?: return
            draft = saved.draft
            attachments = saved.attachments
            terminalComposer.draft = saved.terminalDraft
            terminalComposer.open = saved.terminalComposerOpen
            questionText = saved.questionText
            picks = saved.picks
            answers = saved.answers.mapValues { (_, state) ->
                if (state == "sent") AnswerState.Unconfirmed
                else AnswerState.Failed(Message(R.string.chat_queue_uncertain))
            }
            saved.messages.asReversed().filter { it.phase == QueuedMessagePhase.Waiting }.forEach(::restoreMessage)
            queuedMessages = saved.messages.filter { it.phase != QueuedMessagePhase.Waiting }
                .map { if (it.confirmed || it.phase == QueuedMessagePhase.Failed) it else it.copy(phase = QueuedMessagePhase.Uncertain, heldForTyping = false) }
        } catch (e: Exception) {
            // Leave an unreadable/newer journal intact. Never overwrite or dispatch it.
            recoveryFailed = true
            sendError = e.message
        } finally {
            recoveryLoaded = true
        }
        saveOutbox()
    }

    private fun returnWaitingToDraft() {
        queuedMessages.asReversed().filter { it.phase == QueuedMessagePhase.Waiting }.forEach(::restoreMessage)
        queuedMessages = queuedMessages.filterNot { it.phase == QueuedMessagePhase.Waiting }
        saveOutbox()
    }

    fun cancelQueuedMessage(id: String) {
        val message = queuedMessages.find { it.id == id && it.phase == QueuedMessagePhase.Waiting } ?: return
        queuedMessages = queuedMessages.filterNot { it.id == id }
        restoreMessage(message)
        saveOutbox()
    }

    val canRetryMessage: Boolean get() = canEdit && !interrupting && !uploading &&
        connectionError == null && link?.state == LinkState.Up && snapshot?.status == ChatStatus.LIVE

    fun restoreFailedMessage(id: String) {
        val message = queuedMessages.find { it.id == id && it.phase in setOf(QueuedMessagePhase.Uncertain, QueuedMessagePhase.Failed) } ?: return
        queuedMessages = queuedMessages.filterNot { it.id == id }
        restoreMessage(message)
        saveOutbox()
    }

    /** Only an explicit user action can retry an RPC whose reply was lost. */
    fun retryMessage(id: String) {
        if (!canRetryMessage) return
        val message = queuedMessages.find { it.id == id && it.phase in setOf(QueuedMessagePhase.Uncertain, QueuedMessagePhase.Failed) } ?: return
        val retry = message.copy(phase = QueuedMessagePhase.Waiting, confirmed = false, heldForTyping = false,
            error = null, before = snapshot?.entries.orEmpty().map { it.id }.toSet())
        queuedMessages = queuedMessages.map { if (it.id == id) retry else it }
        waitingConnection = link?.connection
        if (!saveOutbox()) return
        drainQueue()
    }

    private fun restoreUnsentMessage(message: QueuedChatMessage) {
        queuedMessages = queuedMessages.filterNot { it.id == message.id }
        restoreMessage(message)
    }

    private fun restoreMessage(message: QueuedChatMessage) = changeOutbox {
        if (message.draft.isNotEmpty()) {
            // Cancellation/recovery inserts text outside the active IME range.
            // Keep composition and selection attached to the user's newer text.
            val current = input
            val prefix = message.draft + if (current.text.isNotEmpty()) "\n\n" else ""
            input = current.copy(
                text = prefix + current.text,
                selection = TextRange(current.selection.start + prefix.length, current.selection.end + prefix.length),
                composition = current.composition?.let { TextRange(it.start + prefix.length, it.end + prefix.length) },
            )
        }
        attachments = (message.attachments + attachments).distinctBy { it.path }
    }

    /** No retry is safe once terminal.send has started, even if its reply is lost. */
    private fun drainQueue() {
        if (!stopped && queuedMessages.any { it.phase == QueuedMessagePhase.Waiting } && link?.connection !== waitingConnection) {
            returnWaitingToDraft()
            return
        }
        if (stopped || paused || sending != null || interrupting || inputUnavailable ||
            connectionError != null || link?.state != LinkState.Up || snapshot?.status != ChatStatus.LIVE ||
            terminal.agentStatus == AgentStatus.Working || terminal.agentStatus is AgentStatus.Blocked) return
        val message = queuedMessages.firstOrNull { it.phase == QueuedMessagePhase.Waiting } ?: return
        dispatch(message)
    }

    /** Claim each newly observed user entry once, including identical consecutive sends. */
    private fun reconcileQueue() {
        val users = snapshot?.entries.orEmpty().filter { it.body is ChatEntryBody.User }
        var remaining = queuedMessages
        for (entry in users) {
            val index = remaining.indexOfFirst {
                it.phase !in setOf(QueuedMessagePhase.Waiting, QueuedMessagePhase.Failed) && entry.id !in it.before &&
                    (entry.body as ChatEntryBody.User).text.trim() == it.text.trim()
            }
            if (index >= 0) {
                remaining = remaining.filterIndexed { i, _ -> i != index }.map { it.copy(before = it.before + entry.id) }
            }
        }
        if (remaining != queuedMessages) {
            queuedMessages = remaining
            saveOutbox()
        }
    }

    enum class Sending { Sending, Held }

    private var watch: ChatWatch? = null
    private var paused = false
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var terminalPresent by mutableStateOf(true)

    val terminalAvailable: Boolean get() = terminalPresent && terminal.state == "live" && snapshot?.status != ChatStatus.NO_TERMINAL
    /** The transcript can outlive its Agent, and may lag the pane returning to a shell. */
    val inputUnavailable: Boolean get() = !terminalAvailable || terminal.agentStatus == null ||
        snapshot?.status == ChatStatus.ENDED || snapshot?.status == ChatStatus.NO_AGENT
    val showWorking: Boolean get() = terminal.agentStatus == AgentStatus.Working && terminalAvailable &&
        link?.state == LinkState.Up && snapshot?.status in setOf(ChatStatus.LIVE, ChatStatus.WAITING)
    val canEdit: Boolean get() = !inputUnavailable

    /** The Host's Terminals changed: follow this Agent's status. */
    fun update(terminals: List<TerminalInfo>) {
        val now = terminals.find { it.address == terminal.address }
        if (terminalPresent != (now != null) || (now != null &&
            (terminal.agentStatus != now.agentStatus || terminal.state != now.state))) clearNotices()
        terminalPresent = now != null
        if (now == null) return
        terminal = now
        terminalRevision++
        if (now.agentStatus !is AgentStatus.Blocked) refused = false
        drainQueue()
    }

    val canSend: Boolean get() = !recoveryFailed && connectionError == null && link?.state == LinkState.Up &&
        snapshot?.status != ChatStatus.RECONNECTING && canEdit && !interrupting && !uploading && pendingShare == null &&
        (draft.isNotBlank() || attachments.isNotEmpty())

    /** Working Agents keep a cancellable FIFO on the phone until they finish. */
    fun send() {
        if (scope == null || !canSend) return
        val connection = link?.connection
        if (connection == null) {
            sendError = CoreException.Closed().message
            return
        }
        if (queuedMessages.any { it.phase == QueuedMessagePhase.Waiting } && connection !== waitingConnection) {
            returnWaitingToDraft()
            return
        }
        waitingConnection = connection
        // Explicit submission commits the IME text; external recovery does not.
        input = input.copy(composition = null)
        val text = attachments.fold(draft) { message, attachment ->
            message + (if (message.isEmpty() || message.last().isWhitespace()) "" else " ") + shellQuote(attachment.path)
        }
        val message = QueuedChatMessage(draft = draft, attachments = attachments, text = text,
            before = snapshot?.entries.orEmpty().map { it.id }.toSet())
        changeOutbox {
            queuedMessages = queuedMessages + message
            draft = ""
            attachments = emptyList()
            sent = false
            refused = false
            sendError = null
        }
        if (!saveOutbox()) {
            queuedMessages = queuedMessages.filterNot { it.id == message.id }
            restoreMessage(message)
            return
        }
        if (terminal.agentStatus != AgentStatus.Working && sending == null) dispatch(message)
    }

    private fun dispatch(message: QueuedChatMessage) {
        val link = link ?: return
        val conn = link.connection ?: return
        val beforeDispatch = queuedMessages
        queuedMessages = queuedMessages.map {
            if (it.id == message.id) it.copy(phase = QueuedMessagePhase.Agent) else it
        }
        if (!saveOutbox()) {
            queuedMessages = beforeDispatch
            return
        }
        sending = Sending.Sending
        val revisionAtDispatch = terminalRevision
        sendJob = link.finishBeforeDisconnect(conn) {
            try {
                sendDispatchedMessage(conn, message)
            } finally {
                sending = null
                if (!stopped && terminalRevision != revisionAtDispatch) drainQueue()
            }
        }
    }

    /** Transfer the remaining FIFO while its original connection can still accept input. */
    private fun handoffWaitingMessages() {
        val pending = queuedMessages.filter { it.phase == QueuedMessagePhase.Waiting }
        if (pending.isEmpty()) return
        val link = link
        val conn = link?.connection
        if (conn == null || conn !== waitingConnection || link.state != LinkState.Up) {
            returnWaitingToDraft()
            return
        }
        val pendingIds = pending.map { it.id }.toSet()
        val beforeHandoff = queuedMessages
        queuedMessages = queuedMessages.map {
            if (it.id in pendingIds) it.copy(phase = QueuedMessagePhase.Agent) else it
        }
        if (!saveOutbox()) {
            queuedMessages = beforeHandoff
            returnWaitingToDraft()
            return
        }
        val previous = sendJob
        val attempted = mutableSetOf<String>()
        sending = Sending.Sending
        sendJob = link.finishBeforeDisconnect(conn) {
            try {
                previous?.join()
                for (message in pending) {
                    attempted += message.id
                    sending = Sending.Sending
                    if (!sendDispatchedMessage(conn, message)) break
                }
            } finally {
                // A timeout/refusal leaves never-attempted rows safe to edit. An
                // attempted RPC stays unconfirmed because it cannot be recalled.
                pending.filter { it.id !in attempted }.asReversed().forEach(::restoreUnsentMessage)
                sending = null
                saveOutbox()
            }
        }
    }

    private suspend fun sendDispatchedMessage(conn: HostConnection, message: QueuedChatMessage): Boolean {
        try {
            return when (val outcome = withTimeout(25_000L) { conn.sendMessage(terminal.address, message.text, HeldListener(message.id)) }) {
                SendOutcome.Sent -> {
                    sent = true
                    queuedMessages = queuedMessages.map {
                        if (it.id == message.id) it.copy(confirmed = true, heldForTyping = false) else it
                    }
                    inputHistory.remember(message.text)
                    true
                }
                is SendOutcome.Blocked -> {
                    queuedMessages = queuedMessages.map {
                        if (it.id == message.id) it.copy(phase = QueuedMessagePhase.Failed, heldForTyping = false) else it
                    }
                    refused = true
                    terminal = terminal.copy(
                        agentStatus = AgentStatus.Blocked(outcome.reason, outcome.message),
                        needsAttention = true,
                    )
                    false
                }
            }
        } catch (e: TimeoutCancellationException) {
            queuedMessages = queuedMessages.map {
                if (it.id == message.id) it.copy(phase = QueuedMessagePhase.Uncertain, heldForTyping = false) else it
            }
            return false
        } catch (e: CancellationException) {
            queuedMessages = queuedMessages.map {
                if (it.id == message.id) it.copy(phase = QueuedMessagePhase.Uncertain, heldForTyping = false) else it
            }
            throw e
        } catch (e: CoreException) {
            if (e is CoreException.Rpc && e.kind in setOf("no_agent", "status_mismatch")) {
                queuedMessages = queuedMessages.map {
                    if (it.id == message.id) it.copy(phase = QueuedMessagePhase.Failed, heldForTyping = false, error = e.message) else it
                }
                if (e.kind == "no_agent") terminal = terminal.copy(agentStatus = null)
            } else {
                queuedMessages = queuedMessages.map {
                    if (it.id == message.id) it.copy(phase = QueuedMessagePhase.Uncertain, heldForTyping = false, error = e.message) else it
                }
            }
            return false
        } finally {
            saveOutbox()
        }
    }

    private inner class HeldListener(private val messageId: String? = null) : SendListener {
        override fun onHeld() {
            if (sending == Sending.Sending) sending = Sending.Held
            queuedMessages = queuedMessages.map {
                if (it.id == messageId) it.copy(heldForTyping = true) else it
            }
        }
    }

    fun start(scope: CoroutineScope, paused: Boolean = this.paused) {
        this.paused = paused
        stopped = false
        restoreOutbox()
        this.scope = scope
        connectionJob?.cancel()
        connectionJob = scope.launch {
            // ChatWatch has its own SSH line. A live transcript cannot prove
            // that the connection owning a phone-local queue survived.
            snapshotFlow { link?.state to link?.connection }.collect { (state, connection) ->
                clearNotices()
                if (queuedMessages.any { it.phase == QueuedMessagePhase.Waiting } &&
                    (state != LinkState.Up || connection !== waitingConnection)) returnWaitingToDraft()
            }
        }
        job?.cancel()
        job = scope.launch {
            var attempt = 0
            var w: ChatWatch
            while (true) {
                if (paused) return@launch
                try {
                    w = withContext(keyWork) { core.watchChat(host.id, terminal.address, snapshot?.cursor) }
                    break
                } catch (e: CoreException) {
                    clearNotices()
                    connectionError = e
                    returnWaitingToDraft()
                    // A cancelled key unlock is not evidence of a missing Beacon.
                    val cached = snapshot ?: try { core.cachedChat(host.id, terminal.address) } catch (_: CoreException) { null }
                    snapshot = ChatSnapshot(cached?.entries.orEmpty(), ChatStatus.RECONNECTING, cached?.cursor, cached?.usage)
                    if (!e.retryableConnection()) return@launch
                    delay(RETRY_DELAYS_MS[minOf(attempt++, RETRY_DELAYS_MS.lastIndex)])
                }
            }
            watch = w
            if (paused) w.pause()
            val entries = LinkedHashMap<String, ChatEntry>().apply {
                snapshot?.entries?.forEach { put(it.id, it) }
            }
            while (true) {
                try {
                    val update = w.nextUpdate()
                    if (update.reset) entries.clear()
                    update.upserts.forEach { entries[it.id] = it }
                    entries.keys.retainAll(update.order.toSet())
                    if (snapshot?.status != update.status) clearNotices()
                    snapshot = ChatSnapshot(update.order.mapNotNull { entries[it] }, update.status, update.cursor, update.usage)
                    connectionError = null
                    if (update.status in setOf(ChatStatus.RECONNECTING, ChatStatus.ENDED, ChatStatus.NO_TERMINAL, ChatStatus.NO_AGENT)) {
                        returnWaitingToDraft()
                    }
                    reconcileQueue()
                    drainQueue()
                } catch (e: CoreException) {
                    clearNotices()
                    returnWaitingToDraft()
                    if (e is CoreException.Closed) return@launch
                    // The core pauses permanent failures until an explicit retry.
                    connectionError = e
                    snapshot = snapshot?.copy(status = ChatStatus.RECONNECTING)
                }
            }
        }
    }

    /** Bytes of a [ChatImage]; blocking but cheap (the core holds them in memory). */
    fun image(id: String): ByteArray? = watch?.image(id)

    fun toggle(id: String) {
        expanded = if (id in expanded) expanded - id else expanded + id
        if (id !in expanded) rawInputsExpanded = rawInputsExpanded - id
    }

    fun toggleRawInput(id: String) {
        rawInputsExpanded = if (id in rawInputsExpanded) rawInputsExpanded - id else rawInputsExpanded + id
    }

    /**
     * The user tapped option [option] of part [part] of question [id]. A
     * lone single-choice question is answered right away, like the Agent's
     * own TUI; otherwise the pick is toggled until [send].
     */
    fun pick(id: String, question: ChatEntryBody.Question, part: Int, option: Int) {
        if (answers[id] == AnswerState.Sending || answers[id] == AnswerState.Held || answers[id] == AnswerState.Sent || answers[id] == AnswerState.Unconfirmed) return
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
        clearNotices()
        saveOutbox()
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
        if (answers[id] == AnswerState.Sending || answers[id] == AnswerState.Held || answers[id] == AnswerState.Sent || answers[id] == AnswerState.Unconfirmed) return
        val mode = questionMode(id)
        if (mode != QuestionMode.Live && mode != QuestionMode.History) return
        val texts = question.questions.indices.map { questionText[id]?.getOrNull(it)?.takeIf(String::isNotBlank) }
        // Single-choice taps submit directly, without going through the
        // draft selection map. Keep their submitted choice visible too.
        picks = picks + (id to chosen.map { it.toSet() })
        answers = answers + (id to AnswerState.Sending)
        clearNotices()
        saveOutbox()
        scope.launch {
            val state = try {
                withContext(keyWork) {
                    if (mode == QuestionMode.History) {
                        val connection = link?.connection ?: throw CoreException.Closed()
                        val parts = question.questions.mapIndexed { i, part ->
                            texts[i] ?: chosen[i].joinToString(", ") { part.options[it].label }
                        }
                        val text = if (parts.size == 1) parts[0] else parts.mapIndexed { i, part -> "${question.questions[i].text}: $part" }.joinToString("\n")
                        when (val outcome = connection.sendMessage(terminal.address, text, object : SendListener {
                            override fun onHeld() {
                                if (answers[id] == AnswerState.Sending) {
                                    answers = answers + (id to AnswerState.Held)
                                    saveOutbox()
                                }
                            }
                        })) {
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
                AnswerState.Failed(if (mode == QuestionMode.History && e is CoreException.AnswerRefused &&
                    e.reason == AnswerProblem.GONE &&
                    (terminal.agentStatus as? AgentStatus.Blocked)?.reason == BlockedReason.QUESTION)
                    Message(R.string.chat_question_asking_now) else answerProblem(e))
            }
            answers = answers + (id to state)
            saveOutbox()
            if (state == AnswerState.Sent) {
                // Key delivery is only a receipt. Keep duplicate-send protection,
                // but expose the Terminal if the same live question does not advance.
                delay(3_000L)
                if (answers[id] == AnswerState.Sent) {
                    answers = answers + (id to AnswerState.Unconfirmed)
                    saveOutbox()
                }
            }
        }
    }

    /** Network/foreground signals may wake transient failures, never permanent ones. */
    fun reconnect() {
        clearNotices()
        if (connectionError?.retryableConnection() == false) return
        if (watch == null) scope?.let(::start) else watch?.reconnect()
    }

    fun retryNow() {
        clearNotices()
        if (connectionError?.retryableConnection() == false) return
        if (watch == null) scope?.let(::start) else watch?.retryNow()
    }

    /** User action: keep the Agent/draft/cache and resume or recreate the watch. */
    fun retryConnection() {
        clearNotices()
        connectionError = null
        if (watch == null || job?.isActive != true) {
            watch?.stop()
            watch = null
            scope?.let(::start)
        } else watch?.reconnect()
    }

    fun pause() {
        handoffWaitingMessages()
        paused = true
        if (watch == null) job?.cancel() else watch?.pause()
    }

    fun resume() {
        if (!paused) return
        paused = false
        if (watch == null && connectionError?.retryableConnection() != false) scope?.let(::start)
        else watch?.resume()
    }

    fun stop() {
        handoffWaitingMessages()
        saveOutbox()
        stopped = true
        connectionJob?.cancel()
        // Explicitly submitted input belongs to HostLink until its bounded handoff
        // finishes. Cancelling these futures here can abandon unsent instructions.
        uploadJob?.cancel()
        job?.cancel()
        watch?.stop()
        watch = null
    }

    init { restoreOutbox() }
}

sealed interface AnswerState {
    data object Sending : AnswerState
    data object Held : AnswerState
    /** The keys reached the Agent; the Chat shows the result when it has it. */
    data object Sent : AnswerState
    /** No transcript acknowledgement after the grace period; never resend automatically. */
    data object Unconfirmed : AnswerState
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
    val rawEntries = snapshot?.entries.orEmpty()
    val preview = rememberChatLocalhostPreview(chat.link, rawEntries, chat.terminal.address, chat.preview)
    val markdownState = rememberChatMarkdown(rawEntries)
    val markdown by markdownState
    val rendered = remember(chat) { ChatRenderedEntries() }
    val entriesState = remember(chat, rendered, markdownState) {
        derivedStateOf(structuralEqualityPolicy()) { rendered.update(chat.snapshot?.entries.orEmpty(), markdownState.value) }
    }
    val entries by entriesState
    val transcript = remember(chat, entriesState, markdownState) { ChatTranscript(entriesState, markdownState) }
    var searchOpen by rememberSaveable(chat) { mutableStateOf(false) }
    var query by rememberSaveable(chat) { mutableStateOf("") }
    var selected by chat::selectedSearchMatch
    val matches by rememberChatSearch(entries, markdown, query)
    val selectedIndex = selectedChatSearchIndex(matches, selected)
    val active = matches.getOrNull(selectedIndex)
    fun closeSearch() { searchOpen = false; query = ""; selected = null }
    BackHandler(enabled = searchOpen, onBack = ::closeSearch)
    val agentCalls = calls?.let { model ->
        model.calls.filter {
            it.host.id == chat.host.id && it.call.terminal == chat.terminal.address &&
                (it.key in model.replying || it.live && (it.answerable || it.key in model.replyNotes))
        }.sortedBy { it.call.atMs }
    }.orEmpty()
    val scroll = rememberChatScrollState(chat, entries, agentCalls.map { "call:${it.key}" } + chat.queuedMessages.map { "queued:${it.id}" } +
        if (chat.showWorking) listOf("working") else emptyList(), markdown, followEnabled = !searchOpen)
    val toggleTool = remember(chat, scroll) {
        { id: String -> scroll.readTool(); chat.toggle(id) }
    }
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
    CompositionLocalProvider(LocalChatSearch provides searchDecoration,
        androidx.compose.ui.platform.LocalUriHandler provides rememberAgentUriHandler(preview, chat.link, chat.terminal.address)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
            AgentScreenHeader(chat.terminal, chat.host.name, onBack, AgentView.CHAT, onView,
                preview = preview, usage = snapshot?.usage, link = chat.link, onTerminalUpdate = { chat.update(listOf(it)) }, onSearch = { searchOpen = true })
            PreviewChips(preview)
            if (preview.selected != null) {
                LocalhostPreviewBrowser(preview, chat.link, Modifier.weight(1f).fillMaxWidth(),
                    change = chat.terminal.turn to rawEntries.mapNotNull { (it.body as? ChatEntryBody.Tool)?.diff },
                    captureEnabled = chat.canEdit && !chat.uploading,
                    onAttachCapture = { content, cleanup ->
                        chat.attach(context.applicationContext, content, cleanup).also { accepted ->
                            if (accepted) preview.selected = null
                        }
                    })
            } else {
                ChatSearchBar(searchOpen, query, selectedIndex + 1, matches.size,
                    onQuery = { query = it; selected = null },
                    onMove = { selected = navigateChatSearch(matches, active, it) },
                    onClose = ::closeSearch,
                )
                val chatLink = chat.link
                val reconnecting = snapshot?.status == ChatStatus.RECONNECTING || snapshot?.status == ChatStatus.NO_CORE
                val loading = snapshot == null || snapshot.status in setOf(ChatStatus.CONNECTING, ChatStatus.RECONNECTING, ChatStatus.NO_CORE)
                val firstLoading = loading && entries.isEmpty() && chat.queuedMessages.isEmpty()
                val chatProblem = chat.connectionError ?: if (snapshot?.status == ChatStatus.NO_BEACON) CoreException.BeaconNotInstalled() else null
                chatLink?.let { link ->
                    val state = when {
                        link.state != LinkState.Up -> link.state
                        chatProblem != null && !chatProblem.retryableConnection() -> LinkState.Down(chatProblem)
                        reconnecting -> LinkState.Reconnecting
                        else -> LinkState.Up
                    }
                    if (!firstLoading || state is LinkState.Down) {
                        ConnectionNotice(link, state, onRetry = { link.retryNow(); chat.retryConnection() })
                    }
                    if (loading && !firstLoading && state == LinkState.Up) {
                        ScreenLoading(stringResource(R.string.chat_loading), compact = true, onRetry = chat::retryConnection)
                    }
                }
                if (!loading && (chatLink == null || (chatLink.state == LinkState.Up && !reconnecting && chatProblem == null))) {
                    statusText(snapshot?.status ?: ChatStatus.CONNECTING)?.let { ChatNotice(it) }
                }
                if (snapshot != null && entries.isEmpty() && chat.queuedMessages.isEmpty() && snapshot.status in setOf(ChatStatus.LIVE, ChatStatus.WAITING)) {
                    Text(stringResource(R.string.chat_empty))
                }
                io.github.shiroel.aries.speech.ReadAloudPlayback(readAloud, showInactiveNotice = false)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Scrollable clearance, rather than a footer that shrinks the viewport.
                    val transcriptPadding = PaddingValues(bottom = if (scroll.following || searchOpen) 0.dp else 64.dp)
                    val transcriptModifier = Modifier.fillMaxSize().chatImeAnchor(scroll.list).testTag("chat-transcript")
                    val historyContent = remember(chat, transcript, readAloud, agentCalls, calls, toggleTool) {
                        chatHistoryContent(transcript, chat, readAloud, agentCalls, calls, toggleTool)
                    }
                    // One LazyColumn owns both providers. Changing follow mode must
                    // keep the same list instance and item keys when reading details.
                    val followingContent: LazyListScope.() -> Unit = {
                        // Keep main's follow renderer inline: its lazy provider and
                        // forced scroll measure must consume the same content update.
                        entries.forEach { entry ->
                            val body = entry.body
                            val source = body.markdownSource()
                            item(key = "transcript:${entry.id}", contentType = body::class) {
                                CompositionLocalProvider(LocalChatSearchEntry provides entry.id) {
                                    if (body is ChatEntryBody.Question) {
                                        QuestionView(chat, entry.id, body)
                                    } else {
                                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (body is ChatEntryBody.Agent || body is ChatEntryBody.Tool) 0.dp else 12.dp)) {
                                            ChatEntryView(entry, markdown[entry.id]?.attachments, chat, expanded = entry.id in chat.expanded, chat::image, readAloud) { toggleTool(entry.id) }
                                        }
                                        if (source == null && body !is ChatEntryBody.Tool) HorizontalDivider()
                                    }
                                }
                            }
                            if (source != null) {
                                chatMarkdownItems(entry.id, markdown[entry.id], thinking = body is ChatEntryBody.Thinking)
                                item(key = "transcript:${entry.id}:footer", contentType = ChatTranscriptContent.Footer::class) {
                                    if (body is ChatEntryBody.User && body.images.isNotEmpty()) {
                                        Column(Modifier.padding(horizontal = 16.dp)) {
                                            body.images.forEach { ChatImageView(it, chat::image) }
                                        }
                                    }
                                    if (body is ChatEntryBody.Agent) body.metadata?.let { metadata ->
                                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                            val key = "response:${entry.id}"
                                            ChatResponseMetadataView(metadata, key in chat.expanded) { toggleTool(key) }
                                        }
                                    }
                                    HorizontalDivider(Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                        items(agentCalls, key = { "call:${it.key}" }, contentType = { "call" }) { entry ->
                            if (calls != null) ConsoleChatCall(entry, calls)
                        }
                        queuedChatMessages(chat)
                    }
                    if (firstLoading && chatProblem?.retryableConnection() != false && chatLink?.state !is LinkState.Down) {
                        ScreenLoading(stringResource(R.string.chat_loading), onRetry = {
                            chat.link?.retryNow()
                            chat.retryConnection()
                        })
                    }
                    LazyColumn(
                        transcriptModifier, state = scroll.list, contentPadding = transcriptPadding,
                        content = if (scroll.following) followingContent else historyContent,
                    )
                    if (!searchOpen && !scroll.following && (entries.isNotEmpty() || agentCalls.isNotEmpty() || chat.queuedMessages.isNotEmpty())) {
                        LatestButton(
                            description = if (scroll.unreadCount == 0) stringResource(R.string.chat_latest_button)
                                else stringResource(R.string.chat_latest_count_button, scroll.unreadCount),
                            onClick = scroll::latest,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                            unreadCount = scroll.unreadCount,
                        )
                    }
                }
                (chat.terminal.agentStatus as? AgentStatus.Blocked)?.let {
                    // A question the Chat shows open is answered with its own buttons;
                    // the Terminal notice is for what the Chat cannot answer.
                    val answerable = entries.any { e ->
                        e.body is ChatEntryBody.Question && chat.questionMode(e.id) == ChatModel.QuestionMode.Live &&
                            chat.answers[e.id] != AnswerState.Unconfirmed
                    }
                    if (!answerable || chat.refused) BlockedNotice(it, refused = chat.refused, onOpenTerminal)
                }
                chat.sendError?.let {
                    AnnouncedText(stringResource(R.string.chat_send_failed, it), color = MaterialTheme.colorScheme.error)
                }
                if (chat.uploading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    ChatNotice(stringResource(R.string.attachments_uploading))
                }
                chat.attachmentError?.let { AnnouncedText(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
                if (chat.inputUnavailable) {
                    ConsoleChatCallSurface {
                        Text(stringResource(if (chat.terminalAvailable) R.string.chat_input_agent_ended else R.string.chat_status_no_terminal))
                        ConsoleButton(onClick = if (chat.terminalAvailable) onOpenTerminal else onBack) {
                            Text(stringResource(if (chat.terminalAvailable) R.string.terminal_open_button else R.string.back_button))
                        }
                    }
                }
                if (chat.link != null) {
                    ChatCatalogControls(chat)
                    MessageInput(chat)
                }
            }
        }
    }
}

/** A remembered provider changes only with row keys/types; payloads are derived per visible row. */
private fun chatHistoryContent(
    transcript: ChatTranscript,
    chat: ChatModel,
    readAloud: io.github.shiroel.aries.speech.ReadAloudState,
    agentCalls: List<CallEntry>,
    calls: CallsModel?,
    toggleTool: (String) -> Unit,
): LazyListScope.() -> Unit = {
    items(transcript.rows, key = { it.key }, contentType = { it.contentType }) { row ->
        ChatTranscriptRowView(row, chat, readAloud, toggleTool)
    }
    items(agentCalls, key = { "call:${it.key}" }, contentType = { "call" }) { entry ->
        if (calls != null) ConsoleChatCall(entry, calls)
    }
    queuedChatMessages(chat)
}

private fun LazyListScope.queuedChatMessages(chat: ChatModel) {
    items(chat.queuedMessages, key = { "queued:${it.id}" }, contentType = { "queued" }) { QueuedMessageBubble(it, chat) }
    if (chat.showWorking) item(key = "working", contentType = "working") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_circle_dot), contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.chat_working), style = MaterialTheme.typography.labelMedium)
                AgentElapsedTime(chat.terminal.statusMs)
            }
            (chat.snapshot?.entries?.lastOrNull { it.body is ChatEntryBody.Tool }?.body as? ChatEntryBody.Tool)?.let {
                Text(it.title, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChatTranscriptRowView(
    row: ChatTranscriptRow,
    chat: ChatModel,
    readAloud: io.github.shiroel.aries.speech.ReadAloudState,
    toggleTool: (String) -> Unit,
) {
    CompositionLocalProvider(LocalChatSearchEntry provides row.entryId) {
        when (val content = row.content) {
            is ChatTranscriptContent.Header -> ChatEntryHeader(content.entry, content.attachments, chat, readAloud, toggleTool)
            is ChatTranscriptContent.Markdown -> ChatMarkdownItem(content.source, content.block, content.index, content.thinking)
            is ChatTranscriptContent.Footer -> ChatEntryFooter(content.images, content.metadata, chat, row.entryId, toggleTool)
        }
    }
}

@Composable
private fun ChatEntryHeader(entry: ChatEntry, attachments: ChatAttachments?, chat: ChatModel, readAloud: io.github.shiroel.aries.speech.ReadAloudState, toggleTool: (String) -> Unit) {
    val body = entry.body
    if (body is ChatEntryBody.Question) {
        QuestionView(chat, entry.id, body)
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (body is ChatEntryBody.Agent || body is ChatEntryBody.Tool) 0.dp else 12.dp)) {
            ChatEntryView(entry, attachments, chat, expanded = entry.id in chat.expanded, chat::image, readAloud) { toggleTool(entry.id) }
        }
        if (body.markdownSource() == null && body !is ChatEntryBody.Tool) HorizontalDivider()
    }
}

@Composable
private fun ChatEntryFooter(images: List<ChatImage>, metadata: ChatResponseMetadata?, chat: ChatModel, entryId: String, toggleDetails: (String) -> Unit) {
    if (images.isNotEmpty()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            images.forEach { ChatImageView(it, chat::image) }
        }
    }
    metadata?.let {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val key = "response:$entryId"
            ChatResponseMetadataView(it, key in chat.expanded) { toggleDetails(key) }
        }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
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
    ChatInterruptNotice(chat)
    DraftAttachments(chat)
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
            enabled = chat.canEdit,
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    if (chat.draft.isEmpty()) {
                        Text(inputLabel, style = inputStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    field()
                }
            },
        )
        val showInterrupt = chat.draft.isEmpty() && chat.attachments.isEmpty() &&
            ((chat.terminalAvailable && chat.terminal.agentStatus == AgentStatus.Working) || chat.interrupting)
        if (showInterrupt) {
            ChatInterruptButton(chat)
        } else {
            TextButton(
                onClick = chat::send,
                enabled = chat.canSend,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RectangleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (chat.interrupting) ChatInterruptProgress()
                Text(stringResource(R.string.chat_send_button), style = inputStyle)
            }
        }
    }
}

/**
 * The Agent is Blocked and Chat has no recognized actionable menu.
 * Keep the Terminal available for unsupported or incomplete prompts.
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
            AnnouncedText(stringResource(R.string.chat_send_refused), color = color, style = MaterialTheme.typography.labelLarge)
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
    AnnouncedText(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ChatEntryView(
    entry: ChatEntry,
    attachments: ChatAttachments?,
    chat: ChatModel,
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
                    stringResource(R.string.chat_you),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(stringResource(if (b.queued) R.string.chat_queue_agent else R.string.chat_delivery_received),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChatAttachmentCards(attachments, chat)
            }
        }
        is ChatEntryBody.Agent -> chat.readAloudTurns[entry.id]?.let { turnText ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                io.github.shiroel.aries.speech.ReadAloudButton(readAloud, entry.id, turnText)
            }
        }
        is ChatEntryBody.Thinking -> Column {
            Text(
                stringResource(R.string.chat_thinking),
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ChatEntryBody.Tool -> ToolView(b, expanded, image, onToggle,
            rawInputExpanded = entry.id in chat.rawInputsExpanded, onToggleRawInput = { chat.toggleRawInput(entry.id) })
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
    rawInputExpanded: Boolean,
    onToggleRawInput: () -> Unit,
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
        ToolContent(tool, expanded, image, onToggle, rawInputExpanded, onToggleRawInput)
    }
}

@Composable
private fun ToolContent(
    tool: ChatEntryBody.Tool,
    expanded: Boolean,
    image: (String) -> ByteArray?,
    onToggle: () -> Unit,
    rawInputExpanded: Boolean,
    onToggleRawInput: () -> Unit,
) {
    val label = stringResource(R.string.chat_tool_expand_label, tool.name)
    val disclosure = stringResource(if (expanded) R.string.chat_details_expanded else R.string.chat_details_collapsed)
    val failed = tool.status == ChatToolStatus.FAILED
    val status = stringResource(when (tool.status) {
        ChatToolStatus.RUNNING -> R.string.chat_tool_running
        ChatToolStatus.FAILED -> R.string.chat_tool_failed
        ChatToolStatus.DONE -> R.string.chat_tool_done
    })
    // Keep the full title's search offsets and links; layout clips it to one line.
    val open = LocalOpenRemoteFile.current
    val color = MaterialTheme.colorScheme.primary
    val title = remember(tool.title, tool.details?.path, open, color) {
        annotateChatFileLinks(chatToolPathText(tool.title, tool.details?.path), open, color)
    }
    Column(Modifier.fillMaxWidth()) {
        ChatTextActions(tool.output?.takeIf { it.isNotEmpty() } ?: tool.input, onClick = onToggle,
            showIndication = false) { actions ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).then(actions)
                    .semantics { contentDescription = label; stateDescription = disclosure },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                    contentDescription = null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                SearchableChatText(tool.name, "tool-name", Modifier.widthIn(max = 120.dp), style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace, maxLines = 1)
                SearchableChatText(
                    title, "tool-title", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    status, style = MaterialTheme.typography.labelSmall,
                    color = when (tool.status) {
                        ChatToolStatus.FAILED -> MaterialTheme.colorScheme.error
                        ChatToolStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        ChatToolStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
        if (expanded) {
            ChatToolDetailsPane {
                tool.details?.let { ChatToolDetailsView(it) }
                if (tool.input.isNotBlank()) ChatRawInput(tool.input, rawInputExpanded, onToggleRawInput)
                tool.diff?.let { DiffView(it, expanded = true) }
                tool.images.forEach { ChatImageView(it, image) }
                tool.output?.let {
                    Text(stringResource(R.string.chat_tool_output), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChatTextActions(it) { modifier ->
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
