package io.github.shiroel.aries.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
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
    private val journalWork: CoroutineContext = Dispatchers.IO,
    internal val delivery: HostChatQueue = HostChatQueue(),
) {
    // The ViewModel retains this Chat owner across Activity recreation. Large payloads
    // stay in memory; they must never be serialized into the Activity's state Bundle.
    internal val dialogs = ChatDialogState()
    internal var waitingSheetOpen by mutableStateOf(false)
    internal var waitingSheetTarget by mutableStateOf<AgentKey?>(null)
    internal var readingSession by mutableStateOf(ChatReadingSession())
    internal val files = RemoteFilesPanel()
    internal val preview = LocalhostPreview()
    internal var selectedSearchMatch by mutableStateOf<ChatSearchMatch?>(null)
    /** A dismissed Tasks question stays closed across reconnects and Activity recreation. */
    internal var dismissedTaskQuestions by mutableStateOf<Set<String>>(emptySet())
        private set

    internal fun dismissTaskQuestion(id: String) {
        dismissedTaskQuestions = dismissedTaskQuestions + id
    }
    var link by mutableStateOf(link)
        private set

    /** Reopening reuses drafts and pending operations; only the live SSH line changes. */
    fun bind(link: HostLink, terminal: TerminalInfo) {
        inputHistory.open = false
        if (this.link !== link) clearSendNotices()
        this.link = link
        update(listOf(terminal))
    }

    val inputHistory = InputHistoryModel(core, host.id, terminal.address)
    /** The Agent's Terminal, its Agent status kept live by [update]. */
    var terminal by mutableStateOf(terminal)
        private set
    var snapshot by mutableStateOf<ChatSnapshot?>(null)
        private set
    /** Image rows retry missing bytes when their watch or its delivery generation changes. */
    var imageRevision by mutableIntStateOf(0)
        private set
    /** Typed failures survive initial watch creation and later SSH reconnects. */
    var connectionError by mutableStateOf<CoreException?>(null)
        private set
    /** Until there is content, one loading body owns the status and retry action. */
    internal val firstLoading: Boolean get() = snapshot?.status.let {
        it == null || it in setOf(ChatStatus.CONNECTING, ChatStatus.RECONNECTING, ChatStatus.NO_CORE)
    } && snapshot?.entries.isNullOrEmpty() && queuedMessages.isEmpty()
    internal val showInitialLoading: Boolean get() = firstLoading &&
        connectionError?.retryableConnection() != false && link?.state !is LinkState.Down
    /** Tool entry IDs and response:<entry ID> disclosures the user expanded. */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set
    internal val threadToolGrouping = ThreadToolGrouping()
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
        if (inputUnavailable || recoveryFailed) return QuestionMode.ReadOnly
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
        saveOutbox(debounce = true)
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
                waitingReturned = false
                saveOutbox(debounce = true)
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
    var sending: Sending?
        get() = delivery.sending
        private set(value) { delivery.sending = value }
    var sent by mutableStateOf(false)
        private set
    /** The Blocked Agent refused the last message; cleared once it is no longer Blocked. */
    var refused by mutableStateOf(false)
        private set
    /** Why the last message could not be sent, other than a refusal. */
    var sendError by mutableStateOf<Message?>(null)
        private set

    var interrupting by mutableStateOf(false)
        private set
    var interruptNotice by mutableStateOf<Message?>(null)
        private set
    val canInterrupt: Boolean get() = !inputUnavailable && terminal.agentStatus == AgentStatus.Working &&
        link?.state == LinkState.Up && link?.connection != null && !interrupting && sending == null

    /** One confirmed attempt, with no reconnect queue or automatic retry. */
    fun interrupt() {
        val scope = scope ?: return
        val confirmed = delivery.confirmingStop
        delivery.confirmStop(false)
        if (confirmed) returnWaitingToDraft()
        if (!canInterrupt) return
        val connection = link?.connection ?: return
        val address = terminal.address
        interrupting = true
        returnWaitingToDraft()
        saveOutbox()
        interruptNotice = null
        val revision = ++interruptRevision
        scope.launch {
            try {
                val notice = interruptMessage(connection.interruptAgent(address))
                if (interruptRevision == revision) interruptNotice = notice
            } catch (_: CoreException) {
                if (interruptRevision == revision) interruptNotice = Message(R.string.chat_interrupt_failed)
            } finally {
                interrupting = false
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
        clearAttachmentNotice()
        attachments = attachments - attachment
    }
    var uploading by mutableStateOf(false)
        private set
    var attachmentError by mutableStateOf<Message?>(null)
        private set
    private var uploadJob: Job? = null
    private var uploadRemaining: List<String> = emptyList()
    var attachmentNotice by mutableStateOf<Message?>(null)
        private set

    /** Keeps completed chips; only the files still outside the draft are canceled. */
    fun cancelUpload() {
        if (!uploading) return
        attachmentRevision++
        attachmentNotice = Message(R.string.attachments_canceled, listOf(uploadRemaining.joinToString(", ")))
        uploadJob?.cancel()
    }

    fun attachmentFailed(error: Exception) {
        if (error is AttachmentTooLarge || (error is CoreException.Rpc && error.kind == "attachment_too_large")) {
            attachmentError = Message(R.string.attachments_too_large, listOf(MAX_ATTACHMENT_BYTES / (1024 * 1024)))
            return
        }
        attachmentError = Message(when {
            error is CoreException.Closed || error is CoreException.Unreachable -> R.string.attachments_connection_failed
            error is java.io.IOException || error is SecurityException -> R.string.attachments_read_failed
            else -> R.string.attachments_failed
        })
    }

    /** Saves bytes and adds attachment chips to the current draft; never calls sendMessage. */
    fun attach(context: Context, content: SharedContent, onUploaded: () -> Unit = {}, cleanup: () -> Unit = {}): Boolean {
        val scope = scope ?: run { cleanup(); return false }
        if (inputUnavailable || uploading) { cleanup(); return false }
        val link = link ?: run { attachmentFailed(CoreException.Closed()); cleanup(); return false }
        val resize = AttachmentSettings.from(context).resizeImages
        clearAttachmentNotice()
        uploading = true
        uploadRemaining = content.uris.map { it.lastPathSegment ?: "attachment" }
        fun append(value: String) {
            appendDraft(value, separator = if (draft.isEmpty() || draft.last().isWhitespace()) "" else " ")
        }
        content.text?.let(::append)
        val revision = attachmentRevision
        // Retain the current line before even reading camera bytes. Leaving the Host
        // can then finish this bounded batch before disconnecting, without replaying a put.
        val initialConnection = link.connection
        val upload: suspend (HostConnection) -> Unit = { connection ->
            try {
                for (uri in content.uris) {
                    val attachment = withContext(Dispatchers.IO) { prepareAttachment(context, uri, resize) }
                    uploadRemaining = listOf(attachment.name) + uploadRemaining.drop(1)
                    if (inputUnavailable) throw CoreException.Closed()
                    val path = try {
                        connection.uploadAttachment(terminal.address, attachment.name, attachment.bytes)
                    } catch (e: CoreException.Closed) {
                        link.lost(connection)
                        throw e
                    }
                    attachments = attachments + DraftAttachment(path, attachment.name, attachment.bytes.size,
                        attachment.bytes.takeIf { remoteMediaKind(attachment.name) == RemoteMediaKind.IMAGE })
                    uploadRemaining = uploadRemaining.drop(1)
                }
                onUploaded()
            } catch (e: TimeoutCancellationException) {
                if (attachmentRevision == revision) attachmentFailed(CoreException.Closed())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attachmentRevision == revision) attachmentFailed(e)
            } finally {
                uploading = false
                cleanup()
            }
        }
        uploadJob = if (initialConnection != null) {
            link.finishBeforeDisconnect(initialConnection) { upload(initialConnection) }
        } else scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // A picker/camera may release the old line. Acquire and retain its
            // replacement before preparing bytes; never retry a put itself.
            try {
                val connection = link.awaitConnection()
                uploadJob = link.finishBeforeDisconnect(connection) { upload(connection) }
            } catch (e: CancellationException) {
                uploading = false
                cleanup()
                throw e
            } catch (e: Exception) {
                if (attachmentRevision == revision) attachmentFailed(e)
                uploading = false
                cleanup()
            }
        }
        return true
    }

    var queuedMessages: List<QueuedChatMessage>
        get() = delivery.messages
        private set(value) {
            delivery.messages = value
            value.filter { it.id.startsWith("history:") }.forEach { message ->
                val id = message.id.removePrefix("history:")
                answers = answers + (id to when (message.phase) {
                    QueuedMessagePhase.Waiting -> AnswerState.Sending
                    QueuedMessagePhase.Agent -> if (message.confirmed) AnswerState.Sent else if (message.heldForTyping) AnswerState.Held else AnswerState.Sending
                    QueuedMessagePhase.Uncertain -> AnswerState.Unconfirmed
                    QueuedMessagePhase.Failed -> AnswerState.Failed(Message(R.string.chat_answer_gone))
                })
            }
        }
    val queueBlocked: Boolean get() = delivery.uncertainBarrier
    private var connectionJob: Job? = null
    var waitingReturned by mutableStateOf(false)
        private set
    private var transcriptReady by mutableStateOf(false)
    private var transcriptConnection: HostConnection? = null
    private var transcriptTerminal by mutableStateOf<TerminalInfo?>(null)

    /** Home previews must come from an active, caught-up watch of this exact turn/status. */
    internal fun currentTranscript(current: TerminalInfo): ChatSnapshot? = snapshot?.takeIf {
        transcriptReady && transcriptConnection === link?.connection && (link == null || link?.state == LinkState.Up) &&
            transcriptTerminal?.let { source ->
                source.turn == current.turn && source.statusMs == current.statusMs && source.agentStatus == current.agentStatus
            } == true
    }
    private var stopped = false
    private var recoveryLoaded = false
    private var outboxEdits = 0
    private var recoveryFailed by mutableStateOf(false)
    var journalError by mutableStateOf<Message?>(null)
        private set
    val needsDraftRecovery: Boolean get() = recoveryFailed
    var discardingDrafts by mutableStateOf(false)
        private set
    val terminalComposer = TerminalComposerState { saveOutbox(debounce = true) }
    // Flushes survive cancellation of the screen's watch scope. Only the timer
    // is cancellable: an already queued write must finish before the next one.
    private var journalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var journalTimer: Job? = null
    private var journalWrite: Job? = null

    private var interruptRevision = 0L
    private var attachmentRevision = 0L

    /** Link/Agent/Chat changes only invalidate feedback about sending. */
    private fun clearSendNotices() {
        sendError = null
        refused = false
    }

    /** Editing starts a new instruction; unrelated lifecycle changes do not. */
    private fun clearNotices() {
        clearSendNotices()
        interruptRevision++
        interruptNotice = null
        clearAttachmentNotice()
    }

    private fun clearAttachmentNotice() {
        attachmentRevision++
        attachmentError = null
        attachmentNotice = null
    }

    /** Callers commit once after moving text and attachments together between draft and queue. */
    private inline fun changeOutbox(change: () -> Unit) {
        outboxEdits++
        try { change() } finally { outboxEdits-- }
    }

    /** Accept a snapshot; [afterSave] reports whether its durable write succeeded. */
    private fun saveOutbox(debounce: Boolean = false, afterSave: ((Boolean) -> Unit)? = null): Boolean {
        if (!recoveryLoaded || outboxEdits > 0) return true
        if (recoveryFailed) return false
        journalTimer?.cancel()
        // Snapshot on the owner dispatcher, encode/seal/write on IO. Never read
        // mutable composer state on the worker or cancel a write mid-checkpoint.
        val state = ChatOutboxState(draft, attachments, queuedMessages,
            terminalComposer.draft, terminalComposer.open, questionText, picks,
            answers.mapValues { (_, answer) -> when (answer) {
                AnswerState.Sending, AnswerState.Held -> "sending"
                AnswerState.Sent, AnswerState.Unconfirmed -> "sent"
                is AnswerState.Failed -> "failed"
            } }, waitingReturned = waitingReturned)
        fun enqueue() {
            val previous = journalWrite
            journalWrite = journalScope.launch {
                previous?.join()
                val saved = try {
                    withContext(journalWork) { core.saveChatOutbox(host.id, terminal.address, state.encode()) }
                    true
                } catch (e: CoreException) {
                    journalError = Message(R.string.chat_drafts_save_failed)
                    false
                }
                if (saved) journalError = null
                afterSave?.invoke(saved)
            }
        }
        if (debounce) journalTimer = journalScope.launch { delay(300); enqueue() }
        else enqueue()
        return true
    }

    private fun restoreOutbox() {
        if (recoveryLoaded) {
            if (recoveryFailed) try {
                if (core.chatOutbox(host.id, terminal.address) == null) {
                    recoveryFailed = false
                    journalError = null
                }
            } catch (_: Exception) { /* Preserve the recovery banner and the unreadable file. */ }
            return
        }
        try {
            val saved = core.chatOutbox(host.id, terminal.address)?.let(::decodeChatOutbox) ?: return
            draft = saved.draft
            attachments = saved.attachments
            waitingReturned = saved.waitingReturned
            terminalComposer.draft = saved.terminalDraft
            terminalComposer.open = saved.terminalComposerOpen
            questionText = saved.questionText
            picks = saved.picks
            answers = saved.answers.mapValues { (_, state) ->
                if (state == "failed") AnswerState.Failed(Message(R.string.chat_queue_uncertain))
                else AnswerState.Unconfirmed
            }
            queuedMessages = saved.messages.map {
                if (it.phase == QueuedMessagePhase.Agent && !it.confirmed) it.copy(phase = QueuedMessagePhase.Uncertain, heldForTyping = false)
                else it
            }
            delivery.restoreTurnFence()
        } catch (e: Exception) {
            // Leave an unreadable/newer journal intact. Never overwrite or dispatch it.
            recoveryFailed = true
            journalError = Message(R.string.chat_drafts_read_failed)
        } finally {
            recoveryLoaded = true
        }
        if (scope != null) saveOutbox()
    }

    /** Explicitly discard only the saved Host journal, retaining the user's current input. */
    fun discardSavedDrafts() {
        if (!recoveryFailed || discardingDrafts) return
        discardingDrafts = true
        journalTimer?.cancel()
        val previous = journalWrite
        journalWrite = journalScope.launch {
            previous?.join()
            try {
                withContext(journalWork) { core.discardChatOutbox(host.id) }
                recoveryFailed = false
                journalError = null
                saveOutbox()
            } catch (e: CoreException) {
                journalError = Message(R.string.chat_drafts_discard_failed)
            } finally {
                discardingDrafts = false
            }
        }
    }

    private fun returnWaitingToDraft() {
        if (queuedMessages.none { it.phase == QueuedMessagePhase.Waiting }) return
        changeOutbox {
            queuedMessages.asReversed().filter { it.phase == QueuedMessagePhase.Waiting }.forEach(::restoreMessage)
            queuedMessages = queuedMessages.filterNot { it.phase == QueuedMessagePhase.Waiting }
            waitingReturned = true
        }
        saveOutbox()
    }

    fun dismissEnteredMessage(id: String) {
        queuedMessages = queuedMessages.filterNot { it.id == id && it.confirmed }
        saveOutbox()
    }

    fun cancelQueuedMessage(id: String) {
        val message = queuedMessages.find { it.id == id && it.phase == QueuedMessagePhase.Waiting } ?: return
        queuedMessages = queuedMessages.filterNot { it.id == id }
        restoreMessage(message)
        saveOutbox()
    }

    val canRetryMessage: Boolean get() = !recoveryFailed && canEdit && !interrupting && !uploading &&
        connectionError == null && link?.state == LinkState.Up && snapshot?.status == ChatStatus.LIVE

    fun restoreFailedMessage(id: String) {
        val message = queuedMessages.find { it.id == id && it.phase in setOf(QueuedMessagePhase.Uncertain, QueuedMessagePhase.Failed) } ?: return
        queuedMessages = queuedMessages.filterNot { it.id == id }
        if (message.phase == QueuedMessagePhase.Uncertain) delivery.rejected(message.id)
        restoreMessage(message)
        saveOutbox()
    }

    /** Only an explicit user action can retry an RPC whose reply was lost. */
    fun retryMessage(id: String) {
        if (!canRetryMessage) return
        val message = queuedMessages.find { it.id == id && it.phase in setOf(QueuedMessagePhase.Uncertain, QueuedMessagePhase.Failed) } ?: return
        delivery.rejected(message.id)
        val retry = message.copy(phase = QueuedMessagePhase.Waiting, confirmed = false, heldForTyping = false,
            error = null, before = snapshot?.entries.orEmpty().map { it.id }.toSet())
        queuedMessages = queuedMessages.map { if (it.id == id) retry else it }
        if (!saveOutbox()) return
    }

    private fun restoreMessage(message: QueuedChatMessage) = changeOutbox {
        if (message.draft.isNotEmpty()) {
            // Cancellation/recovery inserts text outside the active IME range.
            // Keep composition and selection attached to the user's newer text.
            val current = input
            val prefix = message.draft + if (current.text.isNotEmpty()) "\n\n" else ""
            // Recovery is not an input edit: keep unrelated operation receipts.
            editableInput = current.copy(
                text = prefix + current.text,
                selection = TextRange(current.selection.start + prefix.length, current.selection.end + prefix.length),
                composition = current.composition?.let { TextRange(it.start + prefix.length, it.end + prefix.length) },
            )
        }
        attachments = (message.attachments + attachments).distinctBy { it.path }
        if (message.id.startsWith("history:")) answers = answers - message.id.removePrefix("history:")
    }

    /** HostsModel owns the scheduler; navigation never decides delivery eligibility. */
    private fun deliveryEligible(): Boolean = scope != null && !recoveryFailed && !stopped && !paused &&
        transcriptReady && transcriptConnection === link?.connection && !interrupting && !inputUnavailable &&
        connectionError == null && link?.state == LinkState.Up && snapshot?.status == ChatStatus.LIVE &&
        terminal.agentStatus != AgentStatus.Working && terminal.agentStatus !is AgentStatus.Blocked

    /** Claim each newly observed user entry once, including identical consecutive sends. */
    private fun reconcileQueue() {
        val users = snapshot?.entries.orEmpty().filter { it.body is ChatEntryBody.User }
        var remaining = queuedMessages.filterNot {
            it.confirmed && it.dispatchTurn?.let { turn -> terminal.turn > turn } == true
        }
        for (entry in users) {
            val index = remaining.indexOfFirst {
                it.phase !in setOf(QueuedMessagePhase.Waiting, QueuedMessagePhase.Failed) && entry.id !in it.before &&
                    it.matchesTranscript((entry.body as ChatEntryBody.User).text)
            }
            remaining = remaining.filterIndexed { i, message ->
                // A receipt only promises Terminal input. A later User entry
                // supersedes it even when the Agent rewrites bash/image content.
                i != index && !(message.confirmed && !(entry.body as ChatEntryBody.User).queued && entry.id !in message.before)
            }
            if (index >= 0) remaining = remaining.map { it.copy(before = it.before + entry.id) }
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
            (terminal.agentStatus != now.agentStatus || terminal.state != now.state))) clearSendNotices()
        terminalPresent = now != null
        if (now == null || now.state != "live" || now.agentStatus == null || now.agentStatus is AgentStatus.Blocked)
            delivery.confirmStop(false)
        if (now == null || now.state != "live" || now.agentStatus == null) returnWaitingToDraft()
        if (now == null) return
        terminal = now
        delivery.update(now)
        if (now.agentStatus !is AgentStatus.Blocked) refused = false
        reconcileQueue()
    }

    val canSend: Boolean get() = !recoveryFailed && connectionError == null && link?.state == LinkState.Up &&
        snapshot?.status != ChatStatus.RECONNECTING && canEdit && !interrupting && !uploading && pendingShare == null &&
        (draft.isNotBlank() || attachments.isNotEmpty())

    /** Working Agents keep a cancellable FIFO on the phone until they finish. */
    fun send() {
        if (scope == null || !canSend) return
        if (link?.connection == null) {
            sendError = errorMessage(CoreException.Closed())
            return
        }
        // Explicit submission commits the IME text; external recovery does not.
        input = input.copy(composition = null)
        val text = attachments.fold(draft) { message, attachment ->
            message + (if (message.isEmpty() || message.last().isWhitespace()) "" else " ") + shellQuote(attachment.path)
        }
        val message = QueuedChatMessage(draft = draft, attachments = attachments, text = text,
            before = snapshot?.entries.orEmpty().map { it.id }.toSet())
        val returnedBeforeSend = waitingReturned
        changeOutbox {
            queuedMessages = queuedMessages + message
            waitingReturned = false
            draft = ""
            attachments = emptyList()
            sent = false
            refused = false
            sendError = null
        }
        if (!saveOutbox()) {
            queuedMessages = queuedMessages.filterNot { it.id == message.id }
            waitingReturned = returnedBeforeSend
            restoreMessage(message)
            return
        }
    }

    private fun dispatch(message: QueuedChatMessage) {
        val link = link ?: return
        val conn = link.connection ?: return
        // Capture the current transcript immediately before this row's first attempt.
        val dispatched = message.copy(phase = QueuedMessagePhase.Agent, dispatchTurn = terminal.turn,
            before = snapshot?.entries.orEmpty().map { it.id }.toSet())
        queuedMessages = queuedMessages.map { if (it.id == message.id) dispatched else it }
        sending = Sending.Sending
        saveOutbox { saved ->
            // Status/navigation can change while IO is pending. An unattempted
            // row remains Waiting; it must not type on a stale or stopped line.
            if (!saved || stopped || paused || inputUnavailable || interrupting || !transcriptReady ||
                delivery.confirmingStop || transcriptConnection !== conn || snapshot?.status != ChatStatus.LIVE || queuedMessages.none { it.id == message.id } ||
                terminal.agentStatus == AgentStatus.Working || terminal.agentStatus is AgentStatus.Blocked ||
                link.connection !== conn || link.state != LinkState.Up) {
                queuedMessages = queuedMessages.map {
                    if (it.id == message.id) message else it
                }
                sending = null
                if (inputUnavailable) returnWaitingToDraft()
                else if (saved) {
                    saveOutbox()
                }
            } else {
                link.finishBeforeDisconnect(conn) {
                    try {
                        delivery.attempted(dispatched.id, terminal)
                        sendDispatchedMessage(conn, dispatched)
                    } finally {
                        sending = null
                    }
                }
            }
        }
    }

    private var activeHeldListener: HeldListener? = null

    private suspend fun sendDispatchedMessage(conn: HostConnection, message: QueuedChatMessage): Boolean {
        val listener = HeldListener(message.id)
        activeHeldListener = listener
        try {
            return when (val outcome = withTimeout(25_000L) { conn.sendMessage(terminal.address, message.text, listener) }) {
                SendOutcome.Sent -> {
                    sent = true
                    queuedMessages = queuedMessages.map {
                        if (it.id == message.id) it.copy(confirmed = true, heldForTyping = false) else it
                    }
                    inputHistory.remember(message.text)
                    reconcileQueue()
                    true
                }
                is SendOutcome.Blocked -> {
                    delivery.rejected(message.id)
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
                delivery.rejected(message.id)
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
            if (activeHeldListener === listener) activeHeldListener = null
            saveOutbox()
        }
    }

    private inner class HeldListener(private val messageId: String) : SendListener {
        override fun onHeld() {
            // UniFFI progress arrives on a native thread. Read as well as write
            // on the owner dispatcher, after any intervening queue edits.
            scope?.launch(Dispatchers.Main) {
                if (activeHeldListener !== this@HeldListener ||
                    queuedMessages.none { it.id == messageId && it.phase == QueuedMessagePhase.Agent && !it.confirmed }) return@launch
                if (sending == Sending.Sending) sending = Sending.Held
                queuedMessages = queuedMessages.map {
                    if (it.id == messageId) it.copy(heldForTyping = true) else it
                }
            }
        }
    }

    internal val isRunning get() = !stopped && job?.isActive == true

    fun start(scope: CoroutineScope, paused: Boolean = this.paused) {
        if (!stopped && connectionJob?.isActive == true) return
        this.paused = paused
        transcriptReady = false
        stopped = false
        this.scope = scope
        journalScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        restoreOutbox()
        saveOutbox()
        delivery.start(scope, ::deliveryEligible, ::dispatch)
        connectionJob?.cancel()
        connectionJob = scope.launch {
            snapshotFlow { link?.state to link?.connection }.collect { (state, connection) ->
                clearSendNotices()
                transcriptReady = false
                transcriptConnection = null
                if (state == LinkState.Closed) stop()
                else if (state == LinkState.Up && connection != null && connectionError?.retryableConnection() != false) startWatch()
            }
        }
        // Standalone Chat tests may have no HostLink.
        if (link == null) startWatch()
    }

    private fun startWatch() {
        val scope = scope ?: return
        // Late packets from a retired watch cannot certify a replacement send line.
        val sendConnection = link?.connection
        job?.cancel()
        watch?.stop()
        watch = null
        transcriptReady = false
        transcriptConnection = null
        job = scope.launch {
            var attempt = 0
            var w: ChatWatch
            while (true) {
                if (paused) return@launch
                try {
                    w = withContext(keyWork) { core.watchChat(host.id, terminal.address, snapshot?.cursor) }
                    break
                } catch (e: CoreException) {
                    clearSendNotices()
                    connectionError = e
                    transcriptReady = false
                    // A cancelled key unlock is not evidence of a missing Beacon.
                    val cached = snapshot ?: try { core.cachedChat(host.id, terminal.address) } catch (_: CoreException) { null }
                    snapshot = ChatSnapshot(cached?.entries.orEmpty(), ChatStatus.RECONNECTING, cached?.cursor, cached?.usage)
                    if (!e.retryableConnection()) return@launch
                    delay(RETRY_DELAYS_MS[minOf(attempt++, RETRY_DELAYS_MS.lastIndex)])
                }
            }
            watch = w
            imageRevision++
            if (paused) w.pause()
            val entries = LinkedHashMap<String, ChatEntry>().apply {
                snapshot?.entries?.forEach { put(it.id, it) }
            }
            while (true) {
                try {
                    val update = w.nextUpdate()
                    if (update.reset || snapshot?.status != update.status) imageRevision++
                    if (update.reset) entries.clear()
                    update.upserts.forEach { entries[it.id] = it }
                    entries.keys.retainAll(update.order.toSet())
                    if (snapshot?.status != update.status) clearSendNotices()
                    snapshot = ChatSnapshot(update.order.mapNotNull { entries[it] }, update.status, update.cursor, update.usage)
                    connectionError = null
                    transcriptTerminal = terminal
                    transcriptConnection = sendConnection
                    transcriptReady = update.status == ChatStatus.LIVE
                    if (update.status in setOf(ChatStatus.ENDED, ChatStatus.NO_TERMINAL, ChatStatus.NO_AGENT)) {
                        returnWaitingToDraft()
                    }
                    reconcileQueue()
                } catch (e: CoreException) {
                    clearSendNotices()
                    transcriptReady = false
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

    /** Called only after the user confirms a possibly duplicate historical message. */
    fun retryHistoryAnswer(id: String, question: ChatEntryBody.Question) {
        if (answers[id] != AnswerState.Unconfirmed || questionMode(id) != QuestionMode.History || !complete(id, question)) return
        if (queuedMessages.any { it.id == "history:$id" && it.phase == QueuedMessagePhase.Uncertain }) {
            retryMessage("history:$id")
            return
        }
        answers = answers - id
        send(id, question)
    }

    private fun answer(id: String, question: ChatEntryBody.Question, chosen: List<List<Int>>) {
        val scope = scope ?: return
        if (recoveryFailed) return
        if (answers[id] == AnswerState.Sending || answers[id] == AnswerState.Held || answers[id] == AnswerState.Sent || answers[id] == AnswerState.Unconfirmed) return
        val mode = questionMode(id)
        if (mode != QuestionMode.Live && mode != QuestionMode.History) return
        val texts = question.questions.indices.map { questionText[id]?.getOrNull(it)?.takeIf(String::isNotBlank) }
        // Single-choice taps submit directly, without going through the
        // draft selection map. Keep their submitted choice visible too.
        picks = picks + (id to chosen.map { it.toSet() })
        if (mode == QuestionMode.History) {
            val parts = question.questions.mapIndexed { i, part ->
                texts[i] ?: chosen[i].joinToString(", ") { part.options[it].label }
            }
            val text = if (parts.size == 1) parts[0] else parts.mapIndexed { i, part -> "${question.questions[i].text}: $part" }.joinToString("\n")
            // An answer to history is a new instruction, with the same ordering,
            // receipt and uncertainty rules as the composer and notifications.
            answers = answers + (id to AnswerState.Sending)
            recoverReply(QueuedChatMessage(id = "history:$id", draft = text, attachments = emptyList(), text = text, before = emptySet()))
            return
        }
        answers = answers + (id to AnswerState.Sending)
        clearNotices()
        saveOutbox { saved ->
            if (!saved) {
                answers = answers + (id to AnswerState.Failed(Message(R.string.chat_queue_uncertain)))
                return@saveOutbox
            }
            scope.launch {
                val state = try {
                    withContext(keyWork) {
                        if (texts.any { it != null }) {
                            core.answerQuestionWithText(host.id, terminal.address, id, chosen.map { p -> p.map { it.toUInt() } }, texts)
                        } else {
                            core.answerQuestion(host.id, terminal.address, id, chosen.map { p -> p.map { it.toUInt() } })
                        }
                    }
                    AnswerState.Sent
                } catch (e: TimeoutCancellationException) {
                    AnswerState.Unconfirmed
                } catch (e: CancellationException) {
                    answers = answers + (id to AnswerState.Unconfirmed)
                    saveOutbox()
                    throw e
                } catch (e: CoreException) {
                    AnswerState.Failed(answerProblem(e))
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
    }

    private fun restartWatchOrStart() {
        if (connectionJob?.isActive != true) scope?.let(::start)
        else if (link == null || link?.state == LinkState.Up) startWatch()
    }

    /** Network/foreground signals may wake transient failures, never permanent ones. */
    fun reconnect() {
        clearSendNotices()
        if (connectionError?.retryableConnection() == false) return
        if (watch == null) restartWatchOrStart() else watch?.reconnect()
    }

    fun retryNow() {
        clearSendNotices()
        if (connectionError?.retryableConnection() == false) return
        if (watch == null) restartWatchOrStart() else watch?.retryNow()
    }

    /** User action: keep the Agent/draft/cache and resume or recreate the watch. */
    fun retryConnection() {
        clearSendNotices()
        connectionError = null
        if (watch == null || job?.isActive != true) {
            watch?.stop()
            watch = null
            restartWatchOrStart()
        } else watch?.reconnect()
    }

    fun pause() {
        saveOutbox()
        transcriptReady = false
        paused = true
        if (watch == null) job?.cancel() else watch?.pause()
    }

    fun resume() {
        if (!paused) return
        paused = false
        // A biometric Host resumes only after its explicit unlock. The Up
        // observer starts a fresh watch; an offscreen FIFO must not prompt first.
        if (link != null && link?.state != LinkState.Up) return
        if (watch == null && connectionError?.retryableConnection() != false) restartWatchOrStart()
        else watch?.resume()
    }

    internal val active: Boolean get() = !stopped && scope != null
    internal val ownsReplyFifo: Boolean get() = (scope != null && link?.state == LinkState.Up) ||
        sending != null || queuedMessages.any { it.phase != QueuedMessagePhase.Failed }

    internal fun leave() {
        saveOutbox()
        delivery.confirmStop(false)
        if (queuedMessages.isEmpty() && sending == null) stop()
    }

    internal fun confirmInterrupt(open: Boolean) { delivery.confirmStop(open) }

    fun stop() {
        transcriptReady = false
        saveOutbox()
        stopped = true
        connectionJob?.cancel()
        delivery.stop()
        // Submitted uploads and attempted input finish on their original HostLink.
        // Waiting messages stay in the journal until the next live Host owner.
        job?.cancel()
        watch?.stop()
        watch = null
    }

    internal fun recoverReply(message: QueuedChatMessage): Job? {
        if (queuedMessages.none { it.id == message.id }) queuedMessages = queuedMessages + message
        if (stopped && link?.state == LinkState.Up) scope?.let(::start)
        saveOutbox()
        return journalWrite
    }

    init { ChatReplyRecovery.register(core, AgentKey(host.id, terminal.address), this, ::restoreOutbox) }
}

sealed interface AnswerState {
    data object Sending : AnswerState
    data object Held : AnswerState
    /** The keys reached the Agent; the Chat shows the result when it has it. */
    data object Sent : AnswerState
    /** Delivery or transcript acknowledgement is uncertain; never resend automatically. */
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
        AnswerProblem.FAILED -> Message(R.string.chat_answer_failed, listOf(errorMessage(e)))
    }
    else -> Message(R.string.chat_answer_failed, listOf(errorMessage(e)))
}

@Composable
fun ChatScreen(
    chat: ChatModel,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onView: (AgentView) -> Unit = {},
    calls: CallsModel? = null,
    onRewind: (String) -> Unit = {},
) {
    val thread = LocalThreadChat.current
    key(chat) {
        QuestionAnnouncements(chat)
        calls?.let { CallReplyAnnouncements(it, chat.host.id, chat.terminal.address) }
        val status = chat.terminal.agentStatus
        var previousStatus by remember { mutableStateOf(status) }
        val finished = previousStatus == AgentStatus.Working && status != null && status != AgentStatus.Working && chat.link?.state == LinkState.Up
        val message = if (finished) stringResource(R.string.chat_agent_status_announcement, displayName(chat.terminal), stringResource(agentStatusLabel(status))) else null
        AnnounceChanges(status, message)
        SideEffect { previousStatus = status }
    }
    val readAloud = io.github.shiroel.aries.speech.rememberReadAloud(chat)
    val context = androidx.compose.ui.platform.LocalContext.current
    // Every layout and input panel shares this owner, including compact Chat and live answers.
    LaunchedEffect(chat, chat.pendingShare, chat.inputUnavailable, chat.uploading) {
        chat.pendingShare?.takeUnless { chat.inputUnavailable || chat.uploading }?.let { content ->
            if (chat.attach(context.applicationContext, content)) chat.pendingShare = null
        }
    }
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
    val toolGroupsState = remember(chat, transcript) {
        derivedStateOf(structuralEqualityPolicy()) { chat.threadToolGrouping.update(transcript.contents) }
    }
    val toolGroups = if (thread != null) toolGroupsState.value else emptyList()
    val answerQuestion = if (thread != null) threadAnswerQuestion(chat) else null
    val dialogs = chat.dialogs
    val tasks = LocalTasksDetail.current
    val liveTaskQuestion = if (tasks != null) entries.firstOrNull {
        it.body is ChatEntryBody.Question && it.id !in chat.dismissedTaskQuestions &&
            chat.questionMode(it.id) == ChatModel.QuestionMode.Live &&
            chat.answers[it.id] !in setOf(AnswerState.Sending, AnswerState.Held, AnswerState.Sent, AnswerState.Unconfirmed)
    } else null
    LaunchedEffect(chat, liveTaskQuestion?.id) {
        if (dialogs.tasksRequest == null) liveTaskQuestion?.let { dialogs.tasksRequest = TasksRequest.Question(it.id, it.body as ChatEntryBody.Question) }
    }
    val horizontalScrolls = remember(chat) { ChatMarkdownScrolls() }
    SideEffect { horizontalScrolls.retain(rawEntries.mapTo(mutableSetOf()) { it.id }) }
    var searchOpen by rememberSaveable(chat) { mutableStateOf(false) }
    var query by rememberSaveable(chat) { mutableStateOf("") }
    var selected by chat::selectedSearchMatch
    var searchRequest by rememberSaveable(chat) { mutableIntStateOf(0) }
    val searchNavigation = remember(query, searchRequest) { ChatSearchNavigation() }
    val matches by rememberChatSearch(entries, markdown, query)
    val selectedIndex = selectedChatSearchIndex(matches, selected)
    val active = matches.getOrNull(selectedIndex)
    fun closeSearch() { searchOpen = false; query = ""; selected = null }
    BackHandler(enabled = searchOpen, onBack = ::closeSearch)
    val agentCalls = calls?.let { model ->
        model.calls.filter {
            it.host.id == chat.host.id && it.call.terminal == chat.terminal.address &&
                (it.key in model.replying || it.live && (it.answerable || it.key in model.replyNotes ||
                    thread != null && it.call.pushesByDefault && it.call.answer == null))
        }.sortedBy { it.call.atMs }
    }.orEmpty()
    val activeThreadCall = if (thread != null) agentCalls.firstOrNull { it.key == calls?.openedThreadCall }
        ?: agentCalls.sortedByDescending { it.call.urgency.ordinal }.firstOrNull { calls?.isThreadPanelVisible(it) == true }
        else null
    val callKeys = agentCalls.map { "call:${it.key}" }
    val toolUnreadKeys = toolGroups.flatMap { group -> group.entries.map { it.id to group.key } }.toMap()
    val scroll = rememberChatScrollState(chat, entries, callKeys + chat.queuedMessages.map { "queued:${it.id}" } +
        if (chat.showWorking) listOf("working") else emptyList(), markdown, followEnabled = !searchOpen && !dialogs.isOpen,
        callKeys = callKeys, messageKeys = entries.map { toolUnreadKeys[it.id] ?: "transcript:${it.id}" }.distinct())
    val toggleTool = remember(chat, scroll) {
        { id: String -> scroll.readTool(); chat.toggle(id) }
    }
    val currentActive by rememberUpdatedState(active)
    val currentEntries by rememberUpdatedState(entries)
    LaunchedEffect(query, searchRequest) {
        if (query.isEmpty()) return@LaunchedEffect
        val target = snapshotFlow { currentActive }.filterNotNull().first()
        searchNavigation.target = target
        selected = target
        val body = currentEntries.find { it.id == target.field.entryId }?.body
        if (body is ChatEntryBody.Tool && target.field.entryId !in chat.expanded) chat.toggle(target.field.entryId)
        if (thread != null) toolGroups.firstOrNull { group -> group.entries.any { it.id == target.field.entryId } }?.let {
            if (it.key !in chat.expanded) chat.toggle(it.key)
        }
        scroll.following = false
        scroll.list.scrollToItem(target.field.itemIndex)
    }
    val searchRanges = remember(matches) { matches.flatMap { it.ranges }.groupBy { it.field.entryId to it.field.key } }
    val searchDecoration = remember(searchRanges, active, searchNavigation) { ChatSearchDecoration(searchRanges, active, searchNavigation) }
    CompositionLocalProvider(
        LocalChatSearch provides searchDecoration, LocalChatDialogs provides dialogs,
        LocalChatMarkdownScrolls provides horizontalScrolls,
        LocalChatImageRevision provides chat.imageRevision,
        androidx.compose.ui.platform.LocalUriHandler provides rememberAgentUriHandler(preview, chat.link, chat.terminal.address)) {
        ChatDialogs(dialogs, chat, onRewind)
        TasksRequestDialogs(dialogs, chat, calls)
        AgentViewport(
            header = {
                if (thread != null) ThreadChatHeader(chat, onBack, onOpenTerminal, { searchOpen = true }, preview, snapshot?.usage)
                else AgentScreenHeader(chat.terminal, chat.host.name, onBack, AgentView.CHAT, onView,
                    preview = preview, usage = snapshot?.usage, dialogs = dialogs, link = chat.link, onTerminalUpdate = { chat.update(listOf(it)) }, onSearch = { searchOpen = true })
            },
            input = { compact ->
                if (preview.selected == null) {
                    if (thread != null) ThreadChatInput(thread, chat, activeThreadCall, calls, answerQuestion, compact)
                    else if (chat.link != null) {
                        if (!compact) ChatCatalogControls(chat)
                        MessageInput(chat, compact)
                    }
                }
            },
        ) { compact ->
            if (!compact) PreviewChips(preview)
            if (preview.selected != null) {
                LocalhostPreviewBrowser(preview, chat.link, Modifier.weight(1f).fillMaxWidth(),
                    change = chat.terminal.turn to rawEntries.mapNotNull { (it.body as? ChatEntryBody.Tool)?.diff },
                    captureEnabled = chat.canEdit && !chat.uploading,
                    onAttachCapture = { content, cleanup ->
                        chat.attach(context.applicationContext, content, cleanup = cleanup).also { accepted ->
                            if (accepted) preview.selected = null
                        }
                    })
            } else {
                ChatSearchBar(searchOpen, query, selectedIndex + 1, matches.size,
                    onQuery = { query = it; selected = null },
                    onMove = { selected = navigateChatSearch(matches, active, it); searchRequest++ },
                    onClose = ::closeSearch,
                )
                val chatLink = chat.link
                val reconnecting = snapshot?.status == ChatStatus.RECONNECTING || snapshot?.status == ChatStatus.NO_CORE
                val loading = snapshot == null || snapshot.status in setOf(ChatStatus.CONNECTING, ChatStatus.RECONNECTING, ChatStatus.NO_CORE)
                val firstLoading = chat.firstLoading
                val chatProblem = chat.connectionError ?: if (snapshot?.status == ChatStatus.NO_BEACON) CoreException.BeaconNotInstalled() else null
                if (thread == null) chatLink?.let { link ->
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
                if (thread == null && !loading && (chatLink == null || (chatLink.state == LinkState.Up && !reconnecting && chatProblem == null))) {
                    statusText(snapshot?.status ?: ChatStatus.CONNECTING)?.let { ChatNotice(it) }
                }
                chat.journalError?.let { error ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        AnnouncedText(messageText(error), color = MaterialTheme.colorScheme.error)
                        if (chat.needsDraftRecovery) TextButton(
                            onClick = chat::discardSavedDrafts,
                            enabled = !chat.discardingDrafts,
                        ) { Text(stringResource(R.string.chat_drafts_discard_button)) }
                    }
                }
                if (chat.waitingReturned) ChatNotice(stringResource(
                    if (chat.inputUnavailable) R.string.chat_queue_returned_ended else R.string.chat_queue_returned))
                if (chat.inputUnavailable && (chat.draft.isNotEmpty() || chat.attachments.isNotEmpty())) ReturnedChatDraft(chat)
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
                    val threadContent = remember(chat, transcript, toolGroupsState, readAloud, answerQuestion?.id, agentCalls, toggleTool, scroll) {
                        threadHistoryContent(transcript, toolGroupsState, chat, readAloud,
                            answerQuestion?.id, agentCalls, toggleTool, scroll)
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
                                    ChatEntryHeader(entry, markdown[entry.id]?.attachments, chat, readAloud, toggleTool)
                                }
                            }
                            if (source != null) {
                                chatMarkdownItems(entry.id, markdown[entry.id], thinking = body is ChatEntryBody.Thinking)
                                item(key = "transcript:${entry.id}:footer", contentType = ChatTranscriptContent.Footer::class) {
                                    ChatEntryFooter((body as? ChatEntryBody.User)?.images.orEmpty(),
                                        (body as? ChatEntryBody.Agent)?.metadata, chat, entry.id, toggleTool)
                                }
                            }
                        }
                        items(agentCalls, key = { "call:${it.key}" }, contentType = { "call" }) { entry ->
                            if (calls != null) {
                                if (LocalTasksDetail.current != null) TasksCallTrace(entry) else ConsoleChatCall(entry, calls)
                            }
                        }
                        queuedChatMessages(chat)
                    }
                    if (chat.showInitialLoading) {
                        val loadingText = if (thread != null) statusText(snapshot?.status ?: ChatStatus.LIVE) else null
                        ScreenLoading(loadingText ?: stringResource(R.string.chat_loading), onRetry = {
                            chat.link?.retryNow()
                            chat.retryConnection()
                        })
                    }
                    LazyColumn(
                        transcriptModifier, state = scroll.list, contentPadding = transcriptPadding,
                        content = if (thread != null) threadContent else if (scroll.following) followingContent else historyContent,
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
                    val answerableQuestion = (thread == null || activeThreadCall == null) && entries.any { e ->
                        e.body is ChatEntryBody.Question && chat.questionMode(e.id) == ChatModel.QuestionMode.Live &&
                            chat.answers[e.id] != AnswerState.Unconfirmed
                    } && (thread != null || !chat.refused)
                    val answerableCall = calls != null && if (thread != null) activeThreadCall?.answerable == true
                        else agentCalls.any { call -> call.answerable }
                    if (!answerableQuestion && !answerableCall) BlockedNotice(it, refused = chat.refused, onOpenTerminal)
                }
                AnnouncedText(chat.sendError?.let { stringResource(R.string.chat_send_failed, messageText(it)) }, color = MaterialTheme.colorScheme.error)
                if (chat.uploading) {
                    ConsoleProgress(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { ChatNotice(stringResource(R.string.attachments_uploading)) }
                        TextButton(onClick = chat::cancelUpload) { Text(stringResource(R.string.attachments_cancel_button)) }
                    }
                }
                AnnouncedText(chat.attachmentNotice?.let { messageText(it) })
                AnnouncedText(chat.attachmentError?.let { messageText(it) }, color = MaterialTheme.colorScheme.error)
                if (chat.inputUnavailable) {
                    ConsoleChatCallSurface {
                        if (thread == null || snapshot?.status != ChatStatus.NO_TERMINAL ||
                            chat.link?.state != LinkState.Up || chat.connectionError != null) {
                            Text(stringResource(if (chat.terminalAvailable) R.string.chat_input_agent_ended else R.string.chat_status_no_terminal))
                        }
                        ConsoleButton(onClick = if (chat.terminalAvailable) onOpenTerminal else onBack) {
                            Text(stringResource(if (chat.terminalAvailable) R.string.terminal_open_button else R.string.back_button))
                        }
                    }
                }
                if (compact) ChatInterruptNotice(chat)
                DraftAttachments(chat)
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
        if (calls != null) {
            if (LocalTasksDetail.current != null) TasksCallTrace(entry) else ConsoleChatCall(entry, calls)
        }
    }
    queuedChatMessages(chat)
}

internal fun LazyListScope.queuedChatMessages(chat: ChatModel) {
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
internal fun ChatTranscriptRowView(
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
    if (LocalTasksDetail.current != null) {
        TasksActivityEntry(entry, chat) {
            ChatEntryView(entry, attachments, chat, expanded = entry.id in chat.expanded, chat::image, readAloud) { toggleTool(entry.id) }
        }
    } else if (body is ChatEntryBody.Question) {
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
    if (LocalTasksDetail.current != null && entryId == chat.snapshot?.entries?.firstOrNull { it.body is ChatEntryBody.User }?.id) {
        TasksPlan(chat)
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
}

@Composable
internal fun MessageInput(chat: ChatModel, compact: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    var tools by remember(chat) { mutableStateOf(false) }
    if (tools) ChatInputToolsDialog(chat) { tools = false }
    val inputLabel = stringResource(LocalLayoutScreens.current.messageInputLabel)
    val inputStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = LocalLayoutTokens.current.bodyFont,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    val inputFocus = remember(chat) { FocusRequester() }
    val noticeFocus = remember(chat) { FocusRequester() }
    var returningFromInterrupt by remember(chat) { mutableStateOf(false) }
    var noticeHadFocus by remember(chat) { mutableStateOf(false) }
    LaunchedEffect(chat.interrupting, chat.interruptNotice, returningFromInterrupt, compact) {
        if (returningFromInterrupt && !chat.interrupting) {
            if (chat.interruptNotice != null && !compact) noticeFocus.requestFocus()
            else {
                if (chat.canEdit) inputFocus.requestFocus()
                returningFromInterrupt = false
            }
        }
    }
    if (leading == null) HorizontalDivider()
    if (!compact) ChatInterruptNotice(chat, Modifier.focusRequester(noticeFocus).onFocusChanged {
        // Follow a removed result back to the composer, but do not pull focus
        // back after the user has deliberately moved elsewhere.
        if (noticeHadFocus && !it.isFocused && chat.interruptNotice != null && !chat.interrupting) returningFromInterrupt = false
        noticeHadFocus = it.isFocused
    }.focusable())

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        leading?.invoke()
        BasicTextField(
            value = chat.input,
            onValueChange = { chat.input = it },
            modifier = Modifier.weight(1f).focusRequester(inputFocus)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = inputLabel },
            textStyle = inputStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            maxLines = if (compact) 1 else 6,
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
        if (compact && leading == null) ActionIcon(R.drawable.ic_plus, R.string.chat_input_tools_button,
            { tools = true }, enabled = chat.canEdit)
        val showInterrupt = chat.canInterrupt || chat.interrupting || chat.delivery.confirmingStop
        if (showInterrupt) {
            ChatInterruptButton(chat, onConfirmed = { returningFromInterrupt = true })
        }
        if (!showInterrupt || chat.draft.isNotEmpty() || chat.attachments.isNotEmpty()) {
            TextButton(
                onClick = chat::send,
                enabled = chat.canSend,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RectangleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (chat.interrupting) ChatInterruptProgress()
                if (leading != null) Icon(painterResource(R.drawable.ic_send), stringResource(R.string.chat_send_button), Modifier.size(20.dp))
                else Text(stringResource(R.string.chat_send_button), style = inputStyle)
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
internal fun statusText(status: ChatStatus): String? = when (status) {
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
internal fun ChatEntryView(
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
                    Text(stringResource(if (b.queued) LocalLayoutScreens.current.messageQueuedLabel else LocalLayoutScreens.current.messageReceivedLabel),
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
    val patch = if (LocalTasksDetail.current != null) tool.diff?.takeUnless { it.truncated } else null
    val patchStats = remember(patch) {
        patch?.hunks?.flatMap { it.lines }?.let { lines ->
            TaskChangeStats(lines.count { it.startsWith("+") }, lines.count { it.startsWith("-") })
        }
    }
    val accessibleStatus = listOfNotNull(disclosure, status, patchStats?.let {
        stringResource(R.string.tasks_change_stats_announcement, it.added, it.removed)
    }).joinToString(", ")
    Column(Modifier.fillMaxWidth()) {
        ChatTextActions(tool.output?.takeIf { it.isNotEmpty() } ?: tool.input, onClick = onToggle, onClickLabel = label,
            showIndication = false) { actions ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).then(actions)
                    .semantics { stateDescription = accessibleStatus },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                    contentDescription = null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                val taskTool = LocalTasksDetail.current != null && !expanded
                val verb = if (taskTool) tasksToolVerb(tool.name) else null
                if (taskTool) {
                    Text(verb ?: stringResource(R.string.tasks_tool_other), Modifier.widthIn(max = 100.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                } else SearchableChatText(tool.name, "tool-name", Modifier.widthIn(max = 120.dp), style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace, maxLines = 1)
                Column(Modifier.weight(1f)) {
                    if (taskTool && verb == null) SearchableChatText(tool.name, "tool-name",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                    SearchableChatText(
                        title, "tool-title",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (LocalTasksDetail.current != null && patchStats != null)
                        tasksChangeText(patchStats) else AnnotatedString(status),
                    modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall,
                    color = when (tool.status) {
                        ChatToolStatus.FAILED -> MaterialTheme.colorScheme.error
                        ChatToolStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        ChatToolStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
        if (LocalTasksDetail.current != null && !expanded) tool.diff?.let { DiffView(it, expanded = false) }
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
