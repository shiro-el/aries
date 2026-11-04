package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*

private enum class TasksFilter(val label: Int, val tag: String) {
    ALL(R.string.tasks_filter_all, "all"), NEEDS(R.string.tasks_filter_needs, "needs"),
    WORKING(R.string.tasks_filter_working, "working"), DONE(R.string.tasks_filter_done, "done"),
}

@Composable
internal fun TasksHome(model: HostsModel, board: AgentBoard) {
    var filter by rememberSaveable { mutableStateOf(TasksFilter.ALL) }
    var grouped by rememberSaveable { mutableStateOf(false) }
    var queue by board::needsQueue
    val agents = board.agents
    val hostIds = board.hosts.mapTo(mutableSetOf()) { it.id }
    val callWaiting: (CallEntry) -> Boolean = { call ->
        model.calls.isTaskWaiting(call, call.call.terminal?.let { board.find(AgentKey(call.host.id, it)) })
    }
    val needs = taskNeeds(agents, model.calls.calls.filter { it.host.id in hostIds }, model::taskTranscript, callWaiting)
    val banner = model.calls.banner?.takeIf { it.host.id in hostIds && callWaiting(it) }
    SideEffect { queue?.append(needs) }
    val calls = needs.filterIsInstance<TaskNeed.Call>().map { it.entry }
    val visible = agents.filter {
        when (filter) {
            TasksFilter.ALL -> true
            TasksFilter.NEEDS -> needs.any { need -> need is TaskNeed.Agent && need.entry.key == it.key }
            TasksFilter.WORKING -> it.terminal.agentStatus == AgentStatus.Working
            TasksFilter.DONE -> it.terminal.agentStatus == AgentStatus.Done
        }
    }
    val openNeed: (TaskNeed) -> Unit = { need ->
        queue = TasksNeedsQueue(needs, needs.indexOfFirst { it.key == need.key }.coerceAtLeast(0))
    }
    val folds = rememberTreeFolds("tasks-hosts")
    Column(Modifier.fillMaxSize().testTag("tasks-home")) {
        AgentHomeScope(model, board)
        if (queue == null && model.screen is Screen.Agents && banner != null) CallAttentionSlot(onOpen = { entry ->
            val need = needs.firstOrNull { it is TaskNeed.Call && it.entry.key == entry.key }
            if (need == null) model.openCallInbox()
            else { openNeed(need); model.calls.dismissBanner() }
        })
        if (model.pendingShare != null) {
            Text(stringResource(R.string.attachments_choose_agent), Modifier.padding(12.dp))
            TextButton(onClick = model::cancelShare) { Text(stringResource(R.string.cancel_button)) }
        }
        LazyColumn(Modifier.weight(1f)) {
            item(key = "new-task") { TasksNewTaskCard(model, board) }
            item(key = "filters") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TasksFilter.entries.forEach { item ->
                            val count = when (item) {
                                TasksFilter.ALL -> agents.size + calls.size
                                TasksFilter.NEEDS -> needs.size
                                TasksFilter.WORKING -> agents.count { it.terminal.agentStatus == AgentStatus.Working }
                                TasksFilter.DONE -> agents.count { it.terminal.agentStatus == AgentStatus.Done }
                            }
                            FilterChip(selected = filter == item, onClick = {
                                filter = item
                                if (item == TasksFilter.NEEDS && needs.isNotEmpty()) openNeed(needs.first())
                            }, label = { Text(stringResource(R.string.tasks_filter_count, stringResource(item.label), count)) },
                                modifier = Modifier.testTag("tasks-filter-${item.tag}"))
                        }
                    }
                    IconButton(onClick = { grouped = !grouped }, modifier = Modifier.testTag("tasks-view-toggle")) {
                        Icon(painterResource(if (grouped) R.drawable.ic_menu else R.drawable.ic_panels),
                            stringResource(if (grouped) R.string.tasks_list_view else R.string.tasks_host_view), Modifier.size(20.dp))
                    }
                }
                HorizontalDivider()
            }
            if (board.hosts.isEmpty()) item {
                Text(stringResource(R.string.agents_no_hosts), Modifier.padding(16.dp))
                TextButton(onClick = LocalTasksHosts.current) { Text(stringResource(R.string.console_add_host)) }
            }
            board.hosts.forEach { host ->
                val link = board.links[host.id]
                val error = board.watchErrors[host.id] ?: (link?.state as? LinkState.Down)?.error
                val unavailable = board.unavailable[host.id]
                if (link?.state != LinkState.Up || host in board.loadingHosts || error != null || unavailable != null) {
                    item(key = "connection:${host.id}") {
                        TasksHostConnection(host, link, loading = host in board.loadingHosts,
                            lastUpdatedMs = board.lastUpdatedMs(host.id), error = error, unavailable = unavailable)
                    }
                }
            }
            // Calls precede all Agents, so an urgent Call can never disappear below a pinned family.
            if (filter == TasksFilter.ALL || filter == TasksFilter.NEEDS) items(calls, key = { "call:${it.key}" }) { entry ->
                TasksCallRow(entry, model.callCallerName(entry), onOpen = { openNeed(TaskNeed.Call(entry)) })
            }
            if (grouped) {
                board.hosts.forEach { host ->
                    item(key = "host:${host.id}") {
                        TextButton(onClick = { model.openHost(host) }, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }) {
                            Text(host.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    agentTreeItems(visible.filter { it.host.id == host.id }, folds,
                        onOpen = { entry ->
                            val need = needs.filterIsInstance<TaskNeed.Agent>().firstOrNull { it.entry.key == entry.key }
                            if (filter == TasksFilter.NEEDS && need != null) openNeed(need) else model.selectListedAgent(board, entry)
                        }, onPin = { e, pin -> model.pin(board, e, pin) }, sectionNamespace = "tasks:${host.id}",
                        showHost = false, canPin = model::canPin)
                }
            } else items(visible, key = { "task:${it.key.hostId}:${it.key.address}" }) { entry ->
                TasksTaskRow(entry, model.taskPreview(entry), stats = model.taskChangeStats(entry.key, entry.terminal), showHost = board.hosts.size > 1, onOpen = {
                    val need = needs.filterIsInstance<TaskNeed.Agent>().firstOrNull { it.entry.key == entry.key }
                    if (filter == TasksFilter.NEEDS && need != null) openNeed(need) else model.selectListedAgent(board, entry)
                })
            }
            if (visible.isEmpty() && calls.isEmpty() && board.loadingHosts.isEmpty() && board.hosts.isNotEmpty()) item {
                Text(stringResource(R.string.tasks_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    queue?.let { current -> TasksNeedsSheet(model, board, current, onDismiss = { model.closeTaskNeeds(board) }) }
}

@Composable
internal fun tasksAttentionColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < .5f) Brand.Saffron else Color(0xFF8F5E06)

@Composable
internal fun TasksTaskRow(entry: AgentEntry, transcript: ChatSnapshot? = null, stats: TaskChangeStats? = null,
    onLongClick: (() -> Unit)? = null, showHost: Boolean = true, showSpace: Boolean = true, onOpen: () -> Unit) {
    val status = entry.terminal.agentStatus
    val blocked = status as? AgentStatus.Blocked
    val entries = transcript?.entries.orEmpty().let { all -> all.drop(all.indexOfLast { it.body is ChatEntryBody.User } + 1) }
    val lastTool = entries.lastOrNull { it.body is ChatEntryBody.Tool }?.body as? ChatEntryBody.Tool
    val summary = when (status) {
        is AgentStatus.Blocked -> status.message?.takeIf(String::isNotBlank)
            ?: (entries.lastOrNull { (it.body as? ChatEntryBody.Question)?.status == ChatToolStatus.RUNNING }?.body as? ChatEntryBody.Question)?.questions?.firstOrNull()?.text
        is AgentStatus.Error -> status.message?.takeIf(String::isNotBlank)
            ?: (entries.lastOrNull { it.body is ChatEntryBody.AgentError }?.body as? ChatEntryBody.AgentError)?.detail
        AgentStatus.Working -> lastTool?.title?.takeIf(String::isNotBlank)
        else -> stats?.let { stringResource(R.string.tasks_change_stats, it.added, it.removed) }
    }
    val label = when {
        !entry.live -> R.string.tasks_offline
        blocked?.reason == BlockedReason.QUESTION -> R.string.tasks_answer_needed
        blocked?.reason == BlockedReason.PERMISSION -> R.string.chat_permission_label
        else -> status?.let(::agentStatusLabel) ?: R.string.agent_status_idle
    }
    val accent = when {
        !entry.live -> MaterialTheme.colorScheme.onSurfaceVariant
        blocked != null -> tasksAttentionColor()
        status is AgentStatus.Error -> MaterialTheme.colorScheme.error
        status == AgentStatus.Done -> statusColor(status)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    TasksRow(displayName(entry.terminal), stringResource(label), accent, false,
        Modifier.testTag("task-row:${entry.host.id}:${entry.terminal.address}"), onOpen,
        metadata = {
            val meta = listOfNotNull(entry.terminal.argv.firstOrNull()?.substringAfterLast('/')?.takeUnless { it == displayName(entry.terminal) },
                entry.space?.name?.takeIf { showSpace }, entry.host.name.takeIf { showHost }).filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            ChatQueueCount(LocalChatQueueCounts.current(entry.key))
            if (entry.live && entry.terminal.statusMs != null) AgentElapsedTime(entry.terminal.statusMs)
            else if (!entry.live) entry.lastUpdatedMs?.let { Text(agentLastSeen(it), style = MaterialTheme.typography.bodySmall) }
        }, summary = summary, mono = blocked?.reason == BlockedReason.PERMISSION, onLongClick = onLongClick,
        styledSummary = if (status !is AgentStatus.Blocked && status !is AgentStatus.Error && status != AgentStatus.Working)
            stats?.let { tasksChangeText(it) } else null)
}

@Composable
private fun TasksCallRow(entry: CallEntry, callerName: String?, onOpen: () -> Unit) {
    val urgent = entry.call.urgency == CallUrgency.URGENT || entry.call.urgency == CallUrgency.EMERGENCY
    TasksRow(entry.call.title, stringResource(when {
        !entry.live -> R.string.tasks_call_checking
        urgent -> R.string.tasks_urgent_call
        else -> R.string.console_chat_call_label
    }),
        tasksAttentionColor(), urgent, Modifier.testTag("task-call:${entry.key}").presentsCalls(setOf(entry.key)), onOpen,
        metadata = {
            val context = listOfNotNull(entry.host.name, callerName ?: entry.call.terminal).joinToString(" · ")
            Text(context, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            if (entry.live) AgentElapsedTime(entry.call.atMs) else entry.checkedAtMs?.let {
                Text(agentLastSeen(it), style = MaterialTheme.typography.bodySmall)
            }
        }, summary = entry.call.body.takeIf(String::isNotBlank))
}

internal data class TaskStatusChipColors(val background: Color, val foreground: Color)

internal fun taskStatusChipColors(accent: Color, urgent: Boolean, surface: Color): TaskStatusChipColors {
    val background = if (urgent) Brand.Saffron else accent.copy(alpha = .12f).compositeOver(surface)
    return TaskStatusChipColors(background, if (urgent) Brand.Ink else readableAccent(accent, background, surface.luminance() < .5f))
}

@Composable
private fun TasksRow(title: String, label: String, accent: Color, urgent: Boolean, modifier: Modifier,
    onOpen: () -> Unit, metadata: @Composable RowScope.() -> Unit, summary: String?, mono: Boolean = false,
    onLongClick: (() -> Unit)? = null, styledSummary: AnnotatedString? = null) {
    Column(modifier.fillMaxWidth().then(if (urgent) Modifier.background(Brand.Saffron.copy(alpha = .07f))
        .drawBehind { drawLine(Brand.Saffron, Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), 3.dp.toPx()) } else Modifier)
        .combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onLongClick,
            onLongClickLabel = stringResource(R.string.terminals_actions_label)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            val chip = taskStatusChipColors(accent, urgent, MaterialTheme.colorScheme.background)
            Surface(color = chip.background, shape = RoundedCornerShape(4.dp)) {
                Text(label, Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = chip.foreground)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = metadata)
        summary?.takeIf(String::isNotBlank)?.let {
            Text(styledSummary ?: AnnotatedString(it.lineSequence().first()), style = MaterialTheme.typography.bodySmall,
                color = accent, fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
                maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider()
}

/** One compact row owns both first-load and reconnection status for this Host. */
@Composable
internal fun TasksHostConnection(host: Host, link: HostLink?, loading: Boolean = false,
    lastUpdatedMs: Long? = null, error: CoreException? = (link?.state as? LinkState.Down)?.error,
    unavailable: Message? = null, showHost: Boolean = true,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp)) {
    val connecting = loading || link?.state == LinkState.Reconnecting
    val problem = error?.let(::problemFor)
    val reason = unavailable?.let { messageText(it) } ?: problem?.let { messageText(it.body) }
    Row(modifier.fillMaxWidth().testTag("tasks-host-connection:${host.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        val label = listOfNotNull(host.name.takeIf { showHost },
            stringResource(when {
                connecting -> R.string.reconnecting
                error is CoreException.KeyLocked -> R.string.key_locked_title
                else -> R.string.tasks_offline
            }),
            lastUpdatedMs?.let { agentLastSeen(it) }).joinToString(" · ")
        Text(label, Modifier.weight(1f).semantics { reason?.let { stateDescription = it } },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
        if (link != null) {
            TextButton(onClick = link::retryNow) { Text(stringResource(if (error is CoreException.KeyLocked) R.string.key_unlock_button else R.string.connect_retry_button)) }
            if (error != null) IconButton(onClick = { link.settings(error) }) {
                Icon(painterResource(R.drawable.file_type_settings), stringResource(R.string.connect_settings_button),
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
