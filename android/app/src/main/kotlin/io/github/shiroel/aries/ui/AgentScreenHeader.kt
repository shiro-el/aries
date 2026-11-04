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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
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
) {
    var menu by remember(terminal.address) { mutableStateOf(false) }
    var showGithub by remember(terminal.address) { mutableStateOf(false) }
    if (showGithub) GithubDialog(link, terminal) { showGithub = false }
    val colors = MaterialTheme.colorScheme
    val hostPrefix = hostName?.let { stringResource(R.string.console_agent_host_prefix, it) }
    val openFiles = LocalOpenFiles.current
    val quickOpen = LocalQuickOpen.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("console-top-bar"), verticalAlignment = Alignment.CenterVertically) {
        val backLabel = stringResource(R.string.back_button)
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("console-back")) {
            Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = backLabel, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(buildAnnotatedString {
                    hostPrefix?.let { withStyle(SpanStyle(color = colors.onSurfaceVariant, fontSize = 12.sp)) { append(it) } }
                    append(title)
                }, Modifier.weight(1f, fill = false), fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                if (terminal.watched) {
                    val label = stringResource(R.string.agent_watch_label)
                    Canvas(Modifier.padding(start = 4.dp).size(12.dp).semantics { contentDescription = label }) {
                        drawOval(colors.primary, style = Stroke(1.dp.toPx()))
                        drawCircle(colors.primary, radius = size.minDimension / 6)
                    }
                }
            }
            val largeText = accessibleNameLines() > 1
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                terminal.agentStatus?.let { status ->
                    AgentStatusMark(status, modifier = Modifier.size(10.dp))
                    Text(stringResource(agentStatusLabel(status)), color = statusColor(status), fontSize = 10.sp, maxLines = 1)
                }
                Text(terminal.currentCwd ?: stringResource(R.string.console_agent_unknown_folder), Modifier.weight(1f),
                    color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                if (!largeText) AgentElapsedTime(terminal.statusMs, R.string.console_agent_missing_value, R.string.console_agent_unknown_age)
            }
            if (largeText) AgentElapsedTime(terminal.statusMs, R.string.console_agent_missing_value, R.string.console_agent_unknown_age)
        }
        onView?.let { AgentViewSwitch(current, it) }
        if (preview != null && preview.ports.isNotEmpty()) {
            IconButton(onClick = { preview.selected = if (preview.selected != null) null else preview.ports.first() }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_browser_preview), stringResource(R.string.preview_title), Modifier.size(20.dp),
                    tint = if (preview.selected != null) colors.primary else colors.onSurface)
            }
        }
        Box {
            val label = stringResource(R.string.agent_actions_label)
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("agent-actions").semantics { contentDescription = label }) {
                Canvas(Modifier.size(20.dp)) {
                    for (position in listOf(.2f, .5f, .8f)) drawCircle(colors.onSurface, 1.5.dp.toPx(), Offset(size.width / 2, size.height * position))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                openFiles?.let { ActionMenuItem(stringResource(R.string.menu_open_files), R.drawable.ic_folder_open,
                    onClick = { menu = false; it() }, modifier = Modifier.testTag("files-open")) }
                onSearch?.let {
                    val searchLabel = stringResource(if (current == AgentView.CHAT) R.string.chat_search_button else R.string.terminal_search_button)
                    ActionMenuItem(stringResource(R.string.menu_search), R.drawable.ic_search,
                        onClick = { menu = false; it() }, modifier = Modifier.semantics { contentDescription = searchLabel })
                }
                ActionMenuItem(stringResource(R.string.github_button), R.drawable.ic_github,
                    enabled = link?.connection != null, onClick = { menu = false; showGithub = true })
                quickOpen?.let { ActionMenuItem(stringResource(R.string.quick_open_title), R.drawable.ic_terminal_view,
                    onClick = { menu = false; it() }) }
                if (terminal.agentStatus != null) {
                    MenuDivider()
                    WatchAgentMenuItem(terminal, link) { onTerminalUpdate(it); menu = false }
                }
                terminal.currentProgram?.let {
                    MenuDivider()
                    MenuInfo(stringResource(R.string.menu_program_label), it, R.drawable.ic_terminal_view)
                }
            }
        }
    }
    HorizontalDivider()
}
