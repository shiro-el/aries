package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun ThreadShell(model: HostsModel, content: @Composable () -> Unit) {
    ConsoleShell(model, content, drawerContent = { navigate -> ThreadDrawer(model, navigate) },
        onHome = model::openThreadHome, isHome = { it is Screen.Agents })
}

@Composable
internal fun ThreadSecondaryHeader(model: HostsModel, screen: Screen) {
    if (screen is Screen.Chat || screen is Screen.Terminal) return
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        val home = screen is Screen.Agents
        IconButton(onClick = if (home) LocalConsoleMenu.current ?: {} else when (screen) {
            Screen.Settings, is Screen.JumpHosts -> model::settingsBack
            else -> model::openThreadHome
        }, modifier = Modifier.testTag(if (home) "console-menu" else "console-back")) {
            Icon(painterResource(if (home) R.drawable.ic_menu else R.drawable.ic_arrow_left),
                stringResource(if (home) R.string.console_open_drawer else R.string.back_button), Modifier.size(20.dp))
        }
        val title = when (screen) {
            Screen.Settings -> model.settingsHost?.name ?: stringResource(R.string.settings_title)
            Screen.Hosts -> stringResource(R.string.hosts_title)
            is Screen.Browse -> screen.browser.host.name
            is Screen.Connecting -> screen.host.name
            is Screen.JumpHosts -> stringResource(R.string.jumps_title, screen.host.name)
            else -> stringResource(R.string.app_name)
        }
        if (screen is Screen.Browse) HostContextTitle(screen.browser, Modifier.weight(1f))
        else Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
        if (screen is Screen.Browse) HostActions(screen.browser,
            onRunSaved = { model.runSaved(screen.browser, it) }, onNewTerminal = { model.newTerminal(screen.browser) })
        if (home) ThreadNewTaskButton(model)
    }
    HorizontalDivider()
    CallAttentionSlot()
}

@Composable
internal fun ThreadNewTaskButton(model: HostsModel) {
    val history = LocalCreationHostHistory.current
    ActionIcon(R.drawable.ic_square_pen, R.string.new_task_title, {
        val host = model.hosts.find { it.id == model.currentAgentKey?.hostId }
            ?: model.task?.link?.host?.takeIf { it in model.hosts }
            ?: model.hosts.find { it.id == history?.hostId }
            ?: model.hosts.firstOrNull { h -> model.quickOpenSources.any { it.host.id == h.id && it.live } }
            ?: model.hosts.firstOrNull()
        if (host == null) model.startAddingHost()
        else { history?.used(host.id); model.beginThreadTask(host) }
    }, modifier = Modifier.testTag("thread-new-task"))
}

@Composable
internal fun ThreadEmptyHome(model: HostsModel, board: AgentBoard) {
    Column(Modifier.fillMaxSize().testTag("thread-empty-home")) {
        if (model.pendingShare != null) {
            Text(stringResource(R.string.attachments_choose_agent), Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(board.agents.filter { it.terminal.agentStatus != null }, key = { "${it.host.id}:${it.terminal.address}" }) { entry ->
                    ThreadConversationRow(model, entry) { model.openAgent(board, entry, openChat = true) }
                }
            }
            TextButton(onClick = model::cancelShare) { Text(stringResource(R.string.cancel_button)) }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.thread_home_empty_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.thread_home_empty_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ThreadNewTaskEntry(model, board)
        }
    }
}

@Composable
private fun ThreadDrawer(model: HostsModel, navigate: (() -> Unit) -> Unit) {
    var query by remember { mutableStateOf("") }
    val agents = model.drawerAgents.filter { it.terminal.agentStatus != null }.filter { entry ->
        listOf(model.threadTitle(entry), entry.space?.name.orEmpty(), entry.host.name).any { it.contains(query, true) }
    }
    val calls = model.calls.calls.filter { it.answerable }
    val urgent = calls.filter { it.call.urgency >= CallUrgency.URGENT }.mapNotNull { call ->
        call.call.terminal?.let { AgentKey(call.host.id, it) }
    }.toSet()
    val needs = calls.mapNotNull { call -> call.call.terminal?.let { AgentKey(call.host.id, it) } }.toSet()
    val today = LocalDate.now()
    val sections = agents.groupBy { entry ->
        when {
            entry.key in needs || isWaiting(entry.terminal.agentStatus) -> R.string.thread_section_needs
            entry.terminal.agentStatus == AgentStatus.Working -> R.string.thread_section_working
            entry.terminal.statusMs?.toLong()?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() == today } == true -> R.string.thread_section_today
            else -> R.string.thread_section_earlier
        }
    }
    Column(Modifier.fillMaxSize().testTag("thread-drawer")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            ThreadNewTaskButton(model)
        }
        OutlinedTextField(query, { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.thread_search_hint)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { navigate(model::openCallInbox) }, modifier = Modifier.weight(1f).testTag("drawer-calls")) {
                Text(stringResource(R.string.calls_title), Modifier.fillMaxWidth())
            }
            WaitingSummary(model.quickOpenSources, model.drawerAgents,
                onClick = { navigate(model::openCallInbox) })
        }
        LazyColumn(Modifier.weight(1f)) {
            listOf(R.string.thread_section_needs, R.string.thread_section_working,
                R.string.thread_section_today, R.string.thread_section_earlier).forEach { section ->
                val entries = sections[section].orEmpty().sortedWith(compareByDescending<AgentEntry> { it.key in urgent }
                    .thenByDescending { it.terminal.agentStatus is AgentStatus.Error })
                if (entries.isNotEmpty()) {
                    item(key = "section:$section") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(stringResource(section), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entries.size.toString(), style = MaterialTheme.typography.labelLarge) // i18n-ok: bare count
                        }
                    }
                    items(entries, key = { "${it.host.id}:${it.terminal.address}" }) { entry ->
                        ThreadConversationRow(model, entry, entry.key in urgent) {
                            navigate { model.openPushedAgent(entry.host.id, entry.terminal.address, openChat = true) }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f).heightIn(min = 48.dp).testTag("thread-hosts")
                .clickable(role = Role.Button) { navigate(model::back) }.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (model.hosts.isEmpty()) Text(stringResource(R.string.hosts_title))
                model.hosts.forEach { host ->
                    val live = model.quickOpenSources.any { it.host.id == host.id && it.live }
                    val hostLabel = stringResource(if (live) R.string.thread_host_online else R.string.agents_host_offline, host.name)
                    Row(Modifier.semantics { contentDescription = hostLabel }, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(7.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .then(if (live) Modifier.background(statusColor(AgentStatus.Done), CircleShape) else Modifier))
                        Text(host.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ActionIcon(R.drawable.file_type_settings, R.string.settings_title, { navigate(model::openSettings) },
                modifier = Modifier.testTag("settings-open"))
            ThreadNewTaskButton(model)
        }
    }
}

@Composable
private fun ThreadConversationRow(model: HostsModel, entry: AgentEntry, urgent: Boolean = false, onOpen: () -> Unit) {
    val current = model.currentAgentKey == entry.key
    val tone = when {
        urgent -> MaterialTheme.colorScheme.secondaryContainer
        current -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Column(Modifier.fillMaxWidth().background(tone).semantics { selected = current }
        .testTag("thread-agent:${entry.host.id}:${entry.terminal.address}")
        .clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(model.threadTitle(entry), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
        val metadata = listOfNotNull(entry.terminal.currentProgram ?: entry.terminal.argv.firstOrNull()?.substringAfterLast('/'),
            entry.space?.name ?: entry.terminal.cwd.orEmpty().substringAfterLast('/').takeIf { it.isNotBlank() }, entry.host.name).joinToString(" · ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            entry.terminal.agentStatus?.let { AgentStatusMark(it, modifier = Modifier.size(12.dp)) }
            Text(metadata, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
        }
        model.quickOpenSources.firstOrNull { it.host.id == entry.host.id && !it.live }?.let { source ->
            Text(agentLastSeen(source.fetchedAtMs?.toLong()), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (urgent) Text(stringResource(R.string.thread_urgent_call), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}
