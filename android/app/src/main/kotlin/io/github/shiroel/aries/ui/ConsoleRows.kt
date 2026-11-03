package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
internal fun ConsoleChoice(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    TextButton(onClick = onClick, shape = RectangleShape,
        modifier = modifier.semantics { this.selected = selected }.drawBehind {
            if (selected) drawLine(accent, Offset(0f, size.height), Offset(size.width, size.height), 3.dp.toPx())
        }) { label() }
}

/** Two-line Console row shared by Agents and Browse's Terminal leaves. */
@Composable
internal fun ConsoleTerminalRow(
    terminal: TerminalInfo,
    host: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val status = terminal.agentStatus
    val waiting = isWaiting(status)
    val location = stringResource(R.string.terminal_subtitle, host, terminal.address)
    val spokenName = stringResource(R.string.console_agent_accessibility, displayName(terminal), location)
    val description = status?.let { stringResource(agentStatusLabel(it)) } ?: terminal.state
    val sub = if (waiting) statusMessage(status)?.takeIf { it.isNotBlank() } ?: description
        else stringResource(R.string.console_agent_location, host, terminal.cwd ?: terminal.address)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(onClick = onOpen).semantics {
                contentDescription = spokenName
                stateDescription = description
            }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status != null) AgentStatusMark(status) else TerminalMark()
            Column(Modifier.weight(1f)) {
                Text(displayName(terminal), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = if (waiting) statusColor(AgentStatus.Blocked(null, null)) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (status != null) AgentElapsedTime(terminal.statusMs)
        }
        trailing()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
