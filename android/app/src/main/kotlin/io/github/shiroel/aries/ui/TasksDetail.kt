package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*

internal enum class TasksDetailTab { ACTIVITY, CHANGES, TERMINAL }
internal data class TasksDetailPresentation(
    val changes: TasksChangesState,
    val tab: TasksDetailTab,
    val connection: HostConnection?,
    val select: (TasksDetailTab) -> Unit,
)
internal val LocalTasksDetail = staticCompositionLocalOf<TasksDetailPresentation?> { null }

@Composable
internal fun TasksChatDetail(model: HostsModel, chat: ChatModel) {
    var changesOpen by rememberSaveable(chat.host.id, chat.terminal.address) { mutableStateOf(false) }
    val changes = model.taskChanges(AgentKey(chat.host.id, chat.terminal.address))
    val connection = chat.link?.connections?.collectAsState()?.value
    LaunchedEffect(changes, connection, chat.terminal) { changes.observe(connection, chat.terminal) }
    val presentation = TasksDetailPresentation(changes,
        if (changesOpen) TasksDetailTab.CHANGES else TasksDetailTab.ACTIVITY, connection) {
        when (it) {
            TasksDetailTab.ACTIVITY -> changesOpen = false
            TasksDetailTab.CHANGES -> changesOpen = true
            TasksDetailTab.TERMINAL -> model.switchView(AgentView.TERMINAL)
        }
    }
    CompositionLocalProvider(LocalTasksDetail provides presentation) {
        if (changesOpen) Column(Modifier.fillMaxSize()) {
            AgentScreenHeader(chat.terminal, chat.host.name, model::closeChat, AgentView.CHAT, null,
                link = chat.link, usage = chat.snapshot?.usage, onTerminalUpdate = { chat.update(listOf(it)) })
            TasksChanges(changes, chat.link, chat.terminal) { text -> chat.appendDraft(text); changesOpen = false }
        } else ChatScreen(chat, onBack = model::closeChat, onOpenTerminal = { model.switchView(AgentView.TERMINAL) },
            onView = model::switchView, calls = model.calls)
    }
}

@Composable
internal fun TasksTerminalDetail(model: HostsModel, viewer: TerminalViewer, display: TerminalDisplaySettings) {
    // Plain Terminals still use the ordinary Terminal screen.
    if (viewer.terminal.agentStatus == null || viewer.link == null) {
        TerminalScreen(viewer, onBack = model::closeTerminal, displaySettings = display,
            onSelectionSend = model::sendTerminalSelectionToDraft)
        return
    }
    val link = viewer.link!!
    val context = LocalContext.current.applicationContext
    var changesOpen by rememberSaveable(link.host.id, viewer.terminal.address) { mutableStateOf(false) }
    val changes = model.taskChanges(AgentKey(link.host.id, viewer.terminal.address))
    val connection by link.connections.collectAsState()
    LaunchedEffect(changes, connection, viewer.terminal) { changes.observe(connection, viewer.terminal) }
    CompositionLocalProvider(LocalTasksDetail provides TasksDetailPresentation(changes,
        if (changesOpen) TasksDetailTab.CHANGES else TasksDetailTab.TERMINAL, connection) {
        when (it) {
            TasksDetailTab.ACTIVITY -> model.switchView(AgentView.CHAT)
            TasksDetailTab.CHANGES -> changesOpen = true
            TasksDetailTab.TERMINAL -> changesOpen = false
        }
    }) {
        if (changesOpen) Column(Modifier.fillMaxSize()) {
            AgentScreenHeader(viewer.terminal, link.host.name, model::closeTerminal, AgentView.TERMINAL, null,
                link = link, onTerminalUpdate = { viewer.update(listOf(it)) })
            TasksChanges(changes, link, viewer.terminal, onSend = model::sendTerminalSelectionToDraft)
        } else TerminalScreen(viewer, onBack = model::closeTerminal, onView = model::switchView,
            displaySettings = display, onSelectionSend = model::sendTerminalSelectionToDraft,
            onPreviewCapture = { content, cleanup -> model.attachPreviewToChat(context, content, cleanup) })
    }
}

@Composable
internal fun TasksDetailMetadata(terminal: TerminalInfo, hostName: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        terminal.agentStatus?.let {
            Text(stringResource(agentStatusLabel(it)), color = statusColor(it), style = MaterialTheme.typography.labelSmall)
        }
        Text(listOfNotNull(terminal.currentCwd?.substringAfterLast('/'), terminal.currentProgram, hostName).joinToString(" · "),
            Modifier.weight(1f), maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun TasksDetailTabs(presentation: TasksDetailPresentation, terminal: TerminalInfo) {
    val fresh = terminal.agentStatus != AgentStatus.Working && presentation.changes.matches(terminal, presentation.connection)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("tasks-detail-tabs")) {
        TasksDetailTab.entries.forEach { tab ->
            val selected = presentation.tab == tab
            val accent = MaterialTheme.colorScheme.primary
            val label = when (tab) {
                TasksDetailTab.ACTIVITY -> stringResource(R.string.tasks_activity)
                TasksDetailTab.CHANGES -> presentation.changes.changes?.takeIf { fresh }?.let {
                    stringResource(R.string.tasks_changes_count, it.files.size)
                } ?: stringResource(R.string.tasks_changes)
                TasksDetailTab.TERMINAL -> stringResource(R.string.agent_view_terminal)
            }
            Tab(selected = selected, onClick = { presentation.select(tab) },
                modifier = Modifier.testTag("tasks-tab-${tab.name.lowercase()}").drawBehind {
                    if (selected) drawLine(accent, Offset(0f, size.height), Offset(size.width, size.height), 4.dp.toPx())
                }, text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, maxLines = 1)
                        if (tab == TasksDetailTab.CHANGES && fresh) presentation.changes.stats?.let {
                            Text(tasksChangeText(it), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                })
        }
    }
    HorizontalDivider()
}
