package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/** Compact single choices in Browse, worktrees and Launch. */
@Composable
internal fun ConsoleChoice(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: @Composable () -> Unit) {
    Row(
        modifier.heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        label()
        RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
    }
}

/** Parents retain context; descendants read as one compact program/name/status line. */
@Composable
internal fun ConsoleTerminalRow(
    terminal: TerminalInfo,
    host: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    spaceName: String? = null,
    tabName: String? = null,
    compact: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val status = terminal.agentStatus
    val waiting = isWaiting(status)
    val location = stringResource(R.string.terminal_subtitle, host, terminal.address)
    val spokenName = stringResource(R.string.console_agent_accessibility, displayName(terminal), location)
    val description = status?.let { stringResource(agentStatusLabel(it)) } ?: terminal.state
    val folder = stringResource(R.string.console_agent_location, host, terminal.currentCwd ?: terminal.address)
    val sub = listOfNotNull(spaceName, tabName,
        if (waiting) statusMessage(status)?.takeIf { it.isNotBlank() } ?: description else folder).joinToString(" · ")
    val density = androidx.compose.ui.platform.LocalDensity.current
    val largeText = density.fontScale > 1.3f
    val kindWidth = (48 * density.fontScale).dp
    val ageWidth = (56 * density.fontScale).dp
    val titleHeight = with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    val actionsLabel = stringResource(R.string.terminals_actions_label)
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val rowModifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(background)
        .hoverable(interactions).combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onLongClick, onLongClickLabel = actionsLabel)
        .semantics {
            contentDescription = spokenName
            stateDescription = description
            this.selected = selected
        }
    if (compact) {
        Row(rowModifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgramMark(terminal, Modifier.size(14.dp))
            Text(displayName(terminal), Modifier.weight(1f), fontSize = 12.5.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            trailing()
            if (status != null) AgentStatusMark(status, Modifier.size(20.dp)) else TerminalMark()
        }
    } else {
        Row(rowModifier, verticalAlignment = Alignment.Top) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.height(titleHeight), contentAlignment = Alignment.Center) {
                    ProgramMark(terminal, Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(displayName(terminal), style = MaterialTheme.typography.bodyMedium,
                        maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                    if (largeText) {
                        Text(sub, style = MaterialTheme.typography.bodySmall,
                            color = if (waiting) statusColor(AgentStatus.Blocked(null, null)) else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tabName == null) Text(terminal.currentProgram.orEmpty(), Modifier.testTag("terminal-kind:${terminal.address}"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.testTag("terminal-age:${terminal.address}")) {
                            if (status != null) AgentElapsedTime(terminal.statusMs)
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(sub, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = if (waiting) statusColor(AgentStatus.Blocked(null, null)) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                        if (tabName == null) Box(Modifier.width(kindWidth), contentAlignment = Alignment.CenterStart) {
                            Text(terminal.currentProgram.orEmpty(), Modifier.testTag("terminal-kind:${terminal.address}"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.width(ageWidth).testTag("terminal-age:${terminal.address}"), contentAlignment = Alignment.CenterEnd) {
                            if (status != null) AgentElapsedTime(terminal.statusMs)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing()
                if (status != null) AgentStatusMark(status)
            }
        }
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

/** Program artwork is decorative: the row already announces its Terminal identity. */
@Composable
private fun ProgramMark(terminal: TerminalInfo, modifier: Modifier) {
    val icon = when (terminal.currentProgram?.lowercase()) {
        "claude" -> R.drawable.program_claude
        "codex" -> R.drawable.program_codex
        else -> null
    }
    // Codex ships as a one-colour mark, so it follows the text colour in both themes; Claude keeps its own colour.
    if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = modifier,
        tint = if (icon == R.drawable.program_codex) MaterialTheme.colorScheme.onSurface
            else androidx.compose.ui.graphics.Color.Unspecified)
    else Box(modifier, contentAlignment = Alignment.Center) { TerminalMark() }
}

@Composable
internal fun TreeBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.back_button), Modifier.size(20.dp))
    }
}
