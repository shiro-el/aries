package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.TerminalInfo

internal fun isWaiting(status: AgentStatus?) = status is AgentStatus.Blocked || status is AgentStatus.Error

internal fun statusMessage(status: AgentStatus?): String? = when (status) {
    is AgentStatus.Blocked -> status.message
    is AgentStatus.Error -> status.message
    else -> null
}

/** Square selection used by Browse Tabs and the Launch form. */
@Composable
internal fun ConsoleChoice(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    TextButton(onClick = onClick, enabled = enabled, shape = RectangleShape,
        modifier = modifier.semantics { this.selected = selected }.drawBehind {
            if (selected) drawLine(accent, Offset(0f, size.height), Offset(size.width, size.height), 3.dp.toPx())
        }) { label() }
}

/** Two-line row: only the name and location flex; metadata keeps fixed columns. */
@Composable
internal fun ConsoleTerminalRow(
    terminal: TerminalInfo,
    host: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    spaceName: String? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val status = terminal.agentStatus
    val waiting = isWaiting(status)
    val location = stringResource(R.string.terminal_subtitle, host, terminal.address)
    val spokenName = stringResource(R.string.console_agent_accessibility, displayName(terminal), location)
    val description = status?.let { stringResource(agentStatusLabel(it)) } ?: terminal.state
    val folder = stringResource(R.string.console_agent_location, host, terminal.currentCwd ?: terminal.address)
    val sub = listOfNotNull(spaceName, if (waiting) statusMessage(status)?.takeIf { it.isNotBlank() } ?: description else folder)
        .joinToString(" · ")
    val density = androidx.compose.ui.platform.LocalDensity.current
    val kindWidth = (48 * density.fontScale).dp
    val ageWidth = (56 * density.fontScale).dp
    val titleHeight = with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    val actionsLabel = stringResource(R.string.terminals_actions_label)
    Column(modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongClick, onLongClickLabel = actionsLabel).semantics {
        contentDescription = spokenName
        stateDescription = description
    }) {
        Row(verticalAlignment = Alignment.Top) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.height(titleHeight), contentAlignment = Alignment.Center) {
                    if (status != null) AgentStatusMark(status) else TerminalMark()
                }
                Column(Modifier.weight(1f)) {
                    Text(displayName(terminal), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodySmall,
                        color = if (waiting) statusColor(AgentStatus.Blocked(null, null)) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.width(kindWidth).height(titleHeight), contentAlignment = Alignment.CenterStart) {
                    Text(terminal.currentProgram.orEmpty(), Modifier.testTag("terminal-kind:${terminal.address}"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.width(ageWidth).height(titleHeight).testTag("terminal-age:${terminal.address}"), contentAlignment = Alignment.CenterEnd) {
                    if (status != null) AgentElapsedTime(terminal.statusMs)
                }
            }
            trailing()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A plain Terminal has a prompt, with no Agent-status accessibility label. */
@Composable
private fun TerminalMark() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(24.dp).padding(4.dp)) {
        val u = size.width / 16f
        drawLine(color, Offset(3*u, 4*u), Offset(7*u, 8*u), 1.75f*u, StrokeCap.Square)
        drawLine(color, Offset(7*u, 8*u), Offset(3*u, 12*u), 1.75f*u, StrokeCap.Square)
    }
}
