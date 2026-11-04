package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.PERMISSION_TOOL
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.launch

internal val LocalThreadChat = staticCompositionLocalOf<HostsModel?> { null }

internal object ThreadScreens : LayoutScreens() {
    override val usesListPane = false
    @Composable override fun Shell(model: HostsModel, content: @Composable () -> Unit) = ThreadShell(model, content)
    @Composable override fun Header(model: HostsModel, screen: Screen) = ThreadSecondaryHeader(model, screen)
    @Composable override fun Agents(model: HostsModel, board: AgentBoard) = ThreadEmptyHome(model, board)
    @Composable override fun Hosts(model: HostsModel) = HostListScreen(model, tasks = true)
    @Composable override fun Home(model: HostsModel, browser: HostBrowser) {
        BrowseScreen(browser, showHeader = false, onBack = model::back,
            onOpen = { terminal ->
                if (terminal.agentStatus != null) model.openPushedAgent(browser.host.id, terminal.address, openChat = true)
                else model.selectListedTerminal(browser, terminal)
            }, current = model.currentAgentKey, onPin = model::pin,
            onNewTerminal = { model.newTerminal(browser) }, onRunSaved = { model.runSaved(browser, it) },
            hostSections = {
                if (model.beacon.ui == BeaconUi.Known(BeaconState.NotInstalled)) TextButton(
                    onClick = { model.openHostSettings(browser.host) }, modifier = Modifier.testTag("browse-host-settings:${browser.host.id}")) {
                    Text(stringResource(R.string.settings_beacon_missing))
                }
            })
    }
    @Composable override fun NewTask(task: NewTask, onStart: () -> Unit, onOpenChat: () -> Unit, onDismiss: () -> Unit) =
        ThreadNewTaskScreen(task, onStart, onOpenChat, onDismiss)

    @Composable override fun Chat(model: HostsModel, chat: ChatModel) {
        CompositionLocalProvider(LocalThreadChat provides model) {
            ChatScreen(chat, onBack = model::closeChat, onOpenTerminal = { model.openTerminal(chat.terminal) },
                onView = model::switchView, calls = model.calls)
        }
    }
}

/** Keep the same projected rows, keys and search/reading indexes even while tools are collapsed. */
internal fun threadHistoryContent(
    transcript: ChatTranscript,
    groups: List<ThreadToolGroup>,
    chat: ChatModel,
    readAloud: io.github.shiroel.aries.speech.ReadAloudState,
    activeQuestion: String?,
    calls: List<CallEntry>,
    toggle: (String) -> Unit,
    scroll: ChatScrollState,
): LazyListScope.() -> Unit = {
    val byTool = groups.flatMap { group -> group.entries.map { it.id to group } }.toMap()
    val users = transcript.contents.mapNotNull { (it as? ChatTranscriptContent.Header)?.entry }
        .filter { it.body is ChatEntryBody.User }.mapTo(mutableSetOf()) { it.id }
    items(transcript.rows, key = { it.key }, contentType = { it.contentType }) { row ->
        CompositionLocalProvider(LocalChatSearchEntry provides row.entryId) {
            val content = row.content
            val group = byTool[row.entryId]
            when {
                group != null -> {
                    val first = group.entries.first().id == row.entryId
                    val expanded = group.key in chat.expanded
                    if (first && group.entries.size > 1) ThreadToolSummary(group, chat, scroll, toggle, transcript)
                    if (expanded || group.entries.size == 1) {
                        val header = content as ChatTranscriptContent.Header
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            ChatEntryView(header.entry, header.attachments, chat, row.entryId in chat.expanded,
                                chat::image, readAloud) { toggle(row.entryId) }
                        }
                    }
                }
                row.entryId == activeQuestion -> {
                    if (content is ChatTranscriptContent.Header) Column(Modifier.padding(16.dp)) {
                        val question = content.entry.body as ChatEntryBody.Question
                        Text(stringResource(if (question.name == PERMISSION_TOOL) R.string.thread_permission_waiting else R.string.thread_question_waiting), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        question.questions.forEachIndexed { part, question ->
                            SearchableChatText(question.text, "question:$part", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                        }
                    }
                }
                row.entryId in users -> ThreadUserRow(row, chat)
                content is ChatTranscriptContent.Footer -> {
                    content.metadata?.let {
                        val key = "response:${row.entryId}"
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ChatResponseMetadataView(it, key in chat.expanded) { toggle(key) }
                        }
                    }
                }
                else -> ChatTranscriptRowView(row, chat, readAloud, toggle)
            }
        }
    }
    items(calls, key = { "call:${it.key}" }, contentType = { "call" }) { entry ->
        val model = LocalThreadChat.current
        Column(Modifier.fillMaxWidth().testTag("thread-call:${entry.call.id}")
            .clickable(role = Role.Button) { model?.calls?.openThreadCall(entry) }
            .padding(16.dp)) {
            Text(stringResource(R.string.console_chat_call_label), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.call.title, fontWeight = FontWeight.Medium)
        }
    }
    queuedChatMessages(chat)
}

@Composable
private fun ThreadUserRow(row: ChatTranscriptRow, chat: ChatModel) {
    val content = row.content
    val top = content is ChatTranscriptContent.Header
    val bottom = content is ChatTranscriptContent.Footer
    Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp,
        top = if (top) 16.dp else 0.dp, bottom = if (bottom) 12.dp else 0.dp), horizontalAlignment = Alignment.End) {
        val shape = RoundedCornerShape(topStart = if (top) 16.dp else 0.dp, topEnd = if (top) 16.dp else 0.dp,
            bottomStart = if (bottom) 16.dp else 0.dp, bottomEnd = if (bottom) 16.dp else 0.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            when (content) {
                is ChatTranscriptContent.Header -> {
                    Spacer(Modifier.height(8.dp))
                    ChatAttachmentCards(content.attachments, chat)
                }
                is ChatTranscriptContent.Markdown -> ChatMarkdownItem(content.source, content.block, content.index, content.thinking)
                is ChatTranscriptContent.Footer -> Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    content.images.forEach { ChatImageView(it, chat::image) }
                    val user = chat.snapshot?.entries?.find { it.id == row.entryId }?.body as? ChatEntryBody.User
                    Text(stringResource(if (user?.queued == true) R.string.chat_queue_agent else R.string.chat_delivery_received),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ThreadToolSummary(group: ThreadToolGroup, chat: ChatModel, scroll: ChatScrollState,
    toggle: (String) -> Unit, transcript: ChatTranscript) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val fileCounts = buildList {
            if (group.readFiles > 0) add(pluralStringResource(R.plurals.thread_tools_read, group.readFiles, group.readFiles))
            if (group.modifiedFiles.isNotEmpty()) add(pluralStringResource(R.plurals.thread_tools_modified, group.modifiedFiles.size, group.modifiedFiles.size))
        }.joinToString(stringResource(R.string.thread_tools_separator))
        val steps = pluralStringResource(R.plurals.thread_tools_steps, group.entries.size, group.entries.size)
        val summary = if (fileCounts.isEmpty()) steps else stringResource(R.string.thread_tools_summary, fileCounts, steps)
        val disclosure = stringResource(if (group.key in chat.expanded) R.string.chat_details_expanded else R.string.chat_details_collapsed)
        TextButton(onClick = { toggle(group.key) }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.testTag(group.key).semantics { stateDescription = disclosure }) {
            Icon(painterResource(if (group.key in chat.expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                contentDescription = null, modifier = Modifier.size(16.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            group.modifiedFiles.forEach { (path, entry) ->
                AssistChip(onClick = {
                    scroll.readTool()
                    if (group.key !in chat.expanded) chat.toggle(group.key)
                    if (entry.id !in chat.expanded) chat.toggle(entry.id)
                    scope.launch { scroll.list.scrollToItem(transcript.rows.indexOfFirst { it.entryId == entry.id }.coerceAtLeast(0)) }
                }, label = { Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("thread-diff:${entry.id}"))
            }
        }
        if (group.entries.any { (it.body as ChatEntryBody.Tool).status == ChatToolStatus.FAILED }) {
            AnnouncedText(stringResource(R.string.chat_tool_failed), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
        val running = group.entries.lastOrNull { (it.body as ChatEntryBody.Tool).status == ChatToolStatus.RUNNING }?.body as? ChatEntryBody.Tool
        if (running != null && chat.link?.state == LinkState.Up) {
            Text(stringResource(R.string.thread_tool_running, running.name, running.title), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
        }
    }
}

internal fun threadAnswerQuestion(chat: ChatModel): ChatEntry? = chat.snapshot?.entries.orEmpty().firstOrNull { entry ->
    val q = entry.body as? ChatEntryBody.Question
    q?.status == ChatToolStatus.RUNNING && chat.questionMode(entry.id) == ChatModel.QuestionMode.Live
}

@Composable
internal fun ThreadAnswerPanel(chat: ChatModel, entry: ChatEntry) {
    // Large text and long questions scroll inside the panel.
    Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("thread-answer-panel")) {
        CompositionLocalProvider(LocalChatSearchEntry provides entry.id) {
            QuestionView(chat, entry.id, entry.body as ChatEntryBody.Question)
        }
    }
}

@Composable
internal fun ThreadCallPanel(entry: CallEntry, calls: CallsModel, chat: ChatModel? = null, compact: Boolean = false, onDismiss: (() -> Unit)? = null) {
    val urgent = entry.call.urgency in setOf(CallUrgency.URGENT, CallUrgency.EMERGENCY)
    val accent = if (urgent) Color(0xFFF0B23E) else MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("thread-call-panel")) {
        HorizontalDivider(thickness = if (urgent) 3.dp else 1.dp, color = accent)
        if (urgent || onDismiss != null) Row(
            Modifier.fillMaxWidth().background(if (urgent) accent.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainer),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (urgent) Text(stringResource(if (entry.call.urgency == CallUrgency.EMERGENCY) R.string.call_urgency_emergency else R.string.call_urgency_urgent),
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
            else Spacer(Modifier.weight(1f))
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.thread_call_dismiss)) }
        }
        val colors = MaterialTheme.colorScheme
        MaterialTheme(colorScheme = if (urgent) colors.copy(surfaceContainer = accent.copy(alpha = .1f).compositeOver(colors.surface)) else colors) {
            ConsoleChatCall(entry, calls)
        }
        if (entry.call.choices.isEmpty() && chat != null) MessageInput(chat, compact)
    }
}

@Composable
internal fun ThreadChatHeader(chat: ChatModel, onBack: () -> Unit, onTerminal: () -> Unit,
    onSearch: () -> Unit, preview: LocalhostPreview, usage: AgentUsage?) {
    var menu by remember(chat) { mutableStateOf(false) }
    var showUsage by chat.dialogs::showUsage
    val openFiles = LocalOpenFiles.current
    val github = chat.link?.github(chat.terminal.address)
    if (github?.shown == true) GithubDialog(chat.link, chat.terminal, github::dismiss)
    if (showUsage) AgentUsageSheet(usage) { showUsage = false }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        val openMenu = LocalConsoleMenu.current
        IconButton(onClick = openMenu ?: onBack, modifier = Modifier.testTag("console-menu")) {
            Icon(painterResource(if (openMenu != null) R.drawable.ic_menu else R.drawable.ic_arrow_left),
                stringResource(if (openMenu != null) R.string.console_open_drawer else R.string.back_button), Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).clickable(role = Role.Button) { menu = true }.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(LocalThreadChat.current?.threadTitle(chat) ?: displayName(chat.terminal), Modifier.weight(1f, fill = false), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp))
            }
            val status = if (chat.showInitialLoading) null else threadConnectionText(chat)
            if (status != null) AnnouncedText(status, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(stringResource(R.string.thread_chat_subtitle,
                chat.terminal.agentStatus?.let { stringResource(agentStatusLabel(it)) }.orEmpty(), chat.host.name),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LocalThreadChat.current?.let { ThreadNewTaskButton(it) }
        Box {
            val menuLabel = stringResource(R.string.agent_actions_label)
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("agent-actions").semantics { contentDescription = menuLabel }) {
                Text(stringResource(R.string.thread_chat_menu), style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.terminal_open_button)) }, onClick = { menu = false; onTerminal() })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_search)) }, onClick = { menu = false; onSearch() })
                openFiles?.let { DropdownMenuItem(text = { Text(stringResource(R.string.menu_open_files)) }, onClick = { menu = false; it() }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.github_button)) }, enabled = chat.link?.connection != null,
                    onClick = { menu = false; github?.open() })
                DropdownMenuItem(text = { Text(stringResource(R.string.usage_menu_label,
                    stringResource(R.string.usage_title), contextRemaining(usage?.context))) },
                    onClick = { menu = false; showUsage = true })
                if (preview.ports.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.preview_title)) },
                    onClick = { menu = false; preview.selected = preview.ports.first() })
                WatchAgentMenuItem(chat.terminal, chat.link) { chat.update(listOf(it)); menu = false }
            }
        }
    }
}

@Composable
private fun threadConnectionText(chat: ChatModel): String? = when {
    chat.link?.state is LinkState.Down -> stringResource(R.string.agents_host_offline, chat.host.name)
    chat.link?.state == LinkState.Reconnecting -> statusText(ChatStatus.RECONNECTING)
    chat.connectionError != null -> messageText(problemFor(chat.connectionError!!).body)
    else -> statusText(chat.snapshot?.status ?: ChatStatus.CONNECTING)
}

@Composable
internal fun ThreadConnectionGuidance(chat: ChatModel) {
    // The body owns first load; subsequent status changes are announced in the header.
    if (chat.showInitialLoading || chat.snapshot?.status == ChatStatus.NO_TERMINAL &&
        chat.link?.state == LinkState.Up && chat.connectionError == null) return
    val text = threadConnectionText(chat) ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val state = chat.link?.state as? LinkState.Down
        val error = chat.connectionError ?: state?.error ?: if (chat.snapshot?.status == ChatStatus.NO_BEACON) CoreException.BeaconNotInstalled() else null
        if (error != null) {
            val detail = messageText(problemFor(error).body)
            if (detail != text) AnnouncedText(detail, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            TextButton(onClick = { chat.link?.retryNow(); chat.retryConnection() }) { Text(stringResource(R.string.connect_retry_button)) }
            if (error != null) TextButton(onClick = { chat.link?.settings(error) }) { Text(stringResource(R.string.connect_settings_button)) }
        }
    }
}

@Composable
internal fun ThreadComposer(chat: ChatModel, compact: Boolean) {
    ChatCatalogControls(chat) { openTools ->
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column {
                MessageInput(chat, compact, leading = {
                    ActionIcon(R.drawable.ic_plus, R.string.chat_input_tools_button, openTools, enabled = chat.canEdit)
                })
            }
        }
    }
}

@Composable
internal fun ThreadChatInput(model: HostsModel, chat: ChatModel, activeCall: CallEntry?, calls: CallsModel?,
    question: ChatEntry?, compact: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        ThreadWaitingPill(model, chat)
        ThreadConnectionGuidance(chat)
        if (activeCall != null && calls != null) ThreadCallPanel(activeCall, calls, chat, compact, onDismiss = {
            calls.closeThreadCall(activeCall)
        })
        else if (question != null) ThreadAnswerPanel(chat, question)
        else if (chat.link != null) ThreadComposer(chat, compact)
    }
}
