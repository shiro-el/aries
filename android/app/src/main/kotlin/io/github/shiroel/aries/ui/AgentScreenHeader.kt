package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentUsage
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.TerminalInfo

/** Shared compact header; secondary actions never take a permanent transcript row. */
@Composable
internal fun AgentScreenHeader(
    terminal: TerminalInfo,
    hostName: String?,
    onBack: () -> Unit,
    current: AgentView,
    onView: ((AgentView) -> Unit)?,
    title: String = displayName(terminal),
    link: HostLink? = null,
    onTerminalUpdate: (TerminalInfo) -> Unit = {},
    onSearch: (() -> Unit)? = null,
    preview: LocalhostPreview? = null,
    usage: AgentUsage? = null,
) {
    val tasks = LocalTasksDetail.current
    var menu by remember(terminal.address) { mutableStateOf(false) }
    var showUsage by remember(terminal.address) { mutableStateOf(false) }
    if (showUsage) AgentUsageSheet(usage) { showUsage = false }
    val github = link?.github(terminal.address)
    if (github?.shown == true) GithubDialog(link, terminal, github::dismiss)
    val colors = MaterialTheme.colorScheme
    val openFiles = LocalOpenFiles.current
    val quickOpen = LocalQuickOpen.current
    val openDrawer = LocalConsoleMenu.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("console-top-bar"), verticalAlignment = Alignment.CenterVertically) {
        val backLabel = stringResource(R.string.back_button)
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("console-back")) {
            Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = backLabel, modifier = Modifier.size(20.dp))
        }
        Row(Modifier.weight(1f).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (tasks == null) terminal.agentStatus?.let { AgentStatusMark(it, modifier = Modifier.size(20.dp)) }
            Text(title, Modifier.weight(1f, fill = false), fontFamily = if (tasks == null) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (tasks == null) 14.sp else 16.sp, fontWeight = if (tasks == null) FontWeight.Medium else FontWeight.SemiBold,
                maxLines = if (tasks == null) 1 else accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            if (terminal.watched) {
                val label = stringResource(R.string.agent_watch_label)
                Canvas(Modifier.size(12.dp).semantics { contentDescription = label }) {
                    drawOval(colors.primary, style = Stroke(1.dp.toPx()))
                    drawCircle(colors.primary, radius = size.minDimension / 6)
                }
            }
        }
        if (tasks == null) onView?.let { AgentViewSwitch(current, it) }
        val inbox = LocalCallInbox.current
        if (inbox?.calls?.calls?.any { it.answerable } == true) ConsoleInboxAction()
        if (preview != null && preview.ports.isNotEmpty()) {
            IconButton(onClick = { preview.selected = if (preview.selected != null) null else preview.ports.first() }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_browser_preview), stringResource(R.string.preview_title), Modifier.size(20.dp),
                    tint = if (preview.selected != null) colors.primary else colors.onSurface)
            }
        }
        Box {
            val label = stringResource(R.string.agent_actions_label)
            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("agent-actions").semantics { contentDescription = label }) {
                Canvas(Modifier.size(20.dp)) {
                    for (position in listOf(.2f, .5f, .8f)) drawCircle(colors.onSurface, 1.5.dp.toPx(), Offset(size.width / 2, size.height * position))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                AgentHeaderDetails(terminal, hostName)
                MenuDivider()
                openDrawer?.let { open ->
                    ActionMenuItem(stringResource(R.string.console_open_drawer), R.drawable.ic_menu,
                        onClick = { menu = false; open() })
                }
                NextWaitingAgentMenuItem { menu = false }
                if (current == AgentView.CHAT) {
                    val usageLabel = stringResource(R.string.usage_menu_label,
                        stringResource(R.string.usage_title), contextRemaining(usage?.context))
                    ActionMenuItem(usageLabel, R.drawable.ic_history,
                        onClick = { menu = false; showUsage = true },
                        modifier = Modifier.semantics { contentDescription = usageLabel })
                }
                // Keep Call history reachable even when there is no answerable Call.
                inbox?.let { action ->
                    val label = stringResource(R.string.call_inbox_label, action.calls.calls.count { it.answerable })
                    ActionMenuItem(stringResource(R.string.calls_title), R.drawable.ic_chat_view,
                        onClick = { menu = false; action.toggle() },
                        modifier = Modifier.testTag("agent-inbox-menu").semantics { contentDescription = label })
                }
                openFiles?.let { ActionMenuItem(stringResource(R.string.menu_open_files), R.drawable.ic_folder_open,
                    onClick = { menu = false; it() }, modifier = Modifier.testTag("files-open")) }
                onSearch?.let {
                    val searchLabel = stringResource(if (current == AgentView.CHAT) R.string.chat_search_button else R.string.terminal_search_button)
                    ActionMenuItem(stringResource(R.string.menu_search), R.drawable.ic_search,
                        onClick = { menu = false; it() }, modifier = Modifier.semantics { contentDescription = searchLabel })
                }
                ActionMenuItem(stringResource(R.string.github_button), R.drawable.ic_github,
                    enabled = link?.connection != null, onClick = { menu = false; github?.open() })
                quickOpen?.let { ActionMenuItem(stringResource(R.string.quick_open_title), R.drawable.ic_terminal_view,
                    onClick = { menu = false; it() }) }
                if (terminal.agentStatus != null) {
                    MenuDivider()
                    WatchAgentMenuItem(terminal, link) { onTerminalUpdate(it); menu = false }
                }
            }
        }
    }
    if (tasks != null) {
        TasksDetailMetadata(terminal, hostName)
        TasksDetailTabs(tasks, terminal)
    }
    HorizontalDivider()
}

/** Metadata is readable on demand without growing the header at larger font sizes. */
@Composable
private fun AgentHeaderDetails(terminal: TerminalInfo, hostName: String?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.widthIn(min = 240.dp, max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        hostName?.let {
            Text(stringResource(R.string.agent_details_host, it), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
        }
        val folder = terminal.currentCwd ?: stringResource(R.string.console_agent_unknown_folder)
        val folderLabel = stringResource(R.string.agent_details_folder, folder)
        Text(folder, Modifier.semantics { contentDescription = folderLabel },
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            terminal.agentStatus?.let {
                Text(stringResource(agentStatusLabel(it)), color = statusColor(it), style = MaterialTheme.typography.bodySmall)
            }
            AgentElapsedTime(terminal.statusMs, R.string.console_agent_missing_value, R.string.console_agent_unknown_age)
        }
        terminal.currentProgram?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.menu_program_label), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
