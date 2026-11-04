package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus

/** The Agent tab and sidebar deliberately share sections, rows and expansion state. */
internal fun LazyListScope.agentTreeItems(
    entries: List<AgentEntry>,
    folds: TreeFolds,
    onOpen: (AgentEntry) -> Unit,
    onPin: (AgentEntry, Boolean) -> Unit,
    tag: String = "agent-row",
    current: AgentKey? = null,
    sectionNamespace: String = "all",
    sections: Boolean = true,
    actions: (@Composable ColumnScope.(AgentEntry, () -> Unit) -> Unit)? = null,
) {
    val groups = if (sections) agentGroups(entries, AgentListView.Priority)
        else listOf(AgentGroup("terminals", entries, entries.size, 0))
    groups.forEach { group ->
        val groupKey = "section:$sectionNamespace:${group.id}"
        if (sections) item(key = groupKey) {
            TreeSectionRow(agentGroupLabel(group), 0, folds.contains(groupKey), { folds.toggle(groupKey) })
        }
        if (!sections || !folds.contains(groupKey)) {
            // Only parent rows own fold controls; ignore old nested folds from the two-line tree.
            val hidden = agentTreeRows(group.entries).filter { it.depth == 0 && folds.contains(it.value.key.foldKey) }
                .map { it.value.key }.toSet()
            items(agentTreeRows(group.entries, hidden), key = { "$tag:${it.value.key.hostId}\n${it.value.key.address}" }) { row ->
                var menu by remember(row.value.key) { mutableStateOf(false) }
                val entry = row.value
                val descendants = agentDescendants(group.entries, entry.key)
                val compact = row.depth > 0
                TerminalBranch(row.guide, row.depth) {
                    ConsoleTerminalRow(entry.terminal, entry.host.name, { onOpen(entry) },
                        Modifier.testTag("$tag:${entry.host.id}:${entry.terminal.address}"),
                        spaceName = entry.space?.name,
                        tabName = entry.tabName,
                        compact = compact,
                        selected = current == entry.key,
                        onLongClick = if (actions != null || compact && entry.terminal.agentStatus != null) ({ menu = true }) else null,
                        trailing = {
                            if (!compact) {
                                AgentFoldButton(entry, descendants, folds.contains(entry.key.foldKey)) { folds.toggle(entry.key.foldKey) }
                                if (entry.terminal.agentStatus != null) AgentPinButton(entry) { onPin(entry, it) }
                            }
                            Box {
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (compact && entry.terminal.agentStatus != null) {
                                        val pinDescription = pinLabel(entry)
                                        ActionMenuItem(
                                            label = stringResource(if (entry.terminal.pinned) R.string.menu_unpin else R.string.menu_pin),
                                            icon = if (entry.terminal.pinned) R.drawable.agent_pin_off else R.drawable.agent_pin,
                                            modifier = Modifier.semantics { contentDescription = pinDescription },
                                            onClick = { menu = false; onPin(entry, !entry.terminal.pinned) },
                                        )
                                    }
                                    actions?.invoke(this, entry) { menu = false }
                                }
                            }
                        })
                }
            }
        }
    }
}

@Composable
private fun pinLabel(entry: AgentEntry): String = stringResource(
    if (entry.terminal.pinned) R.string.agent_unpin_button_label else R.string.agent_pin_button_label,
    displayName(entry.terminal), entry.host.name,
)

@Composable
private fun agentGroupLabel(group: AgentGroup): String =
    if (group.id == "pinned") stringResource(R.string.agents_pinned_group, group.count)
    else stringResource(R.string.agents_priority_group, stringResource(when (group.priority) {
        4 -> R.string.agents_needs_you
        3 -> R.string.agent_status_done
        2 -> R.string.agent_status_working
        else -> R.string.agent_status_idle
    }), group.count)

@Composable
internal fun TreeChevron(folded: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(14.dp)) {
        val u = size.width / 14
        val points = if (folded) listOf(Offset(5*u, 3*u), Offset(9*u, 7*u), Offset(5*u, 11*u))
            else listOf(Offset(3*u, 5*u), Offset(7*u, 9*u), Offset(11*u, 5*u))
        drawLine(color, points[0], points[1], 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, points[1], points[2], 1.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
internal fun AgentFoldButton(entry: AgentEntry, descendants: List<AgentEntry>, folded: Boolean, onFold: () -> Unit) {
    if (descendants.isEmpty()) return
    val label = stringResource(if (folded) R.string.agents_expand_children else R.string.agents_collapse_children,
        displayName(entry.terminal), descendants.size)
    Row(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onFold)
        .padding(horizontal = 3.dp)
        .testTag("agent-fold:${entry.host.id}:${entry.terminal.address}")
        .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        TreeChevron(folded, Modifier.size(11.dp))
        if (folded) {
            val status = descendants.mapNotNull { childAgentStatus(it.terminal.agentStatus) }
                .maxByOrNull(::agentPriority) ?: AgentStatus.Idle
            val statusLabel = stringResource(agentStatusLabel(status))
            Text(descendants.size.toString(), Modifier.testTag("agent-summary:${entry.host.id}:${entry.terminal.address}")
                .semantics { contentDescription = statusLabel },
                style = MaterialTheme.typography.labelSmall, color = statusColor(status))
        }
    }
}

@Composable
internal fun AgentPinButton(entry: AgentEntry, onPin: (Boolean) -> Unit) {
    val pinned = entry.terminal.pinned
    val label = stringResource(if (pinned) R.string.agent_unpin_button_label else R.string.agent_pin_button_label,
        displayName(entry.terminal), entry.host.name)
    IconButton(onClick = { onPin(!pinned) }, modifier = Modifier.size(48.dp)
        .testTag("agent-pin:${entry.host.id}:${entry.terminal.address}").semantics { selected = pinned }) {
        Icon(painterResource(if (pinned) R.drawable.agent_pin_off else R.drawable.agent_pin), label,
            Modifier.size(18.dp), tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A header toggles in place; expanded is the default, persisted by the caller. */
@Composable
internal fun TreeSectionRow(label: String, depth: Int, folded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (folded) R.string.tree_collapsed else R.string.tree_expanded)
    TextButton(onClick = onToggle, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
        .padding(start = (depth * 16).dp).semantics { stateDescription = state }) {
        TreeChevron(folded)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
