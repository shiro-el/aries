package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus

/** Only actionable counts use row width; stored counts retain their known age. */
@Composable
internal fun WaitingSummary(
    sources: List<QuickOpenSource>,
    agents: List<AgentEntry>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val waiting = agents.filter { isWaiting(it.terminal.agentStatus) }
    val liveCount = waiting.count { it.live }
    val stored = waiting.filterNot { it.live }
    if (waiting.isEmpty()) return
    val partial = sources.any { !it.live && it.fetchedAtMs == null }
    val oldest = stored.mapNotNull { it.lastUpdatedMs }.minOrNull()
    val now = System.currentTimeMillis()
    val knownAge = oldest?.takeIf { age ->
        age > 0 && age <= now && stored.all { it.lastUpdatedMs != null && it.lastUpdatedMs > 0 && it.lastUpdatedMs <= now }
    }
    Column(modifier.then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
        .widthIn(max = 176.dp).heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
        if (liveCount > 0) Text(
            stringResource(if (partial) R.string.agents_waiting_partial else R.string.console_agents_waiting, liveCount),
            style = MaterialTheme.typography.labelMedium, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis,
            color = statusColor(AgentStatus.Blocked(null, null)),
        )
        if (stored.isNotEmpty()) {
            Text(stringResource(R.string.agents_waiting_stored, stored.size),
                style = MaterialTheme.typography.labelMedium, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(agentLastSeen(knownAge), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
        }
    }
}

internal data class NextWaitingAction(val entry: AgentEntry, val open: () -> Unit)
internal val LocalNextWaitingAgent = staticCompositionLocalOf<NextWaitingAction?> { null }

@Composable
internal fun NextWaitingAgentMenuItem(onNavigate: () -> Unit) {
    val next = LocalNextWaitingAgent.current ?: return
    val label = stringResource(R.string.agents_next_waiting, displayName(next.entry.terminal), next.entry.host.name)
    DropdownMenuItem(
        text = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { next.entry.terminal.agentStatus?.let { AgentStatusMark(it, modifier = Modifier.size(20.dp)) } },
        onClick = { onNavigate(); next.open() },
        modifier = Modifier.widthIn(min = 240.dp, max = 320.dp).testTag("next-waiting-agent")
            .semantics { contentDescription = label },
    )
}
