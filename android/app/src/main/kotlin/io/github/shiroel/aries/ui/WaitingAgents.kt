package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import kotlinx.coroutines.delay

/** Unknown Hosts never turn into a reassuring zero; stored counts always retain their age. */
@Composable
internal fun WaitingSummary(
    sources: List<QuickOpenSource>,
    agents: List<AgentEntry>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val count = agents.count { isWaiting(it.terminal.agentStatus) }
    val unknown = sources.any { !it.live && it.fetchedAtMs == null }
    val oldest = sources.filterNot { it.live }.mapNotNull { it.fetchedAtMs }.minOrNull()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    val label = when {
        unknown && count == 0 -> stringResource(R.string.agents_waiting_unknown)
        unknown -> stringResource(R.string.agents_waiting_partial, count)
        else -> stringResource(R.string.console_agents_waiting, count)
    }
    Column(modifier.then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
        .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis,
            color = if (count > 0) statusColor(AgentStatus.Blocked(null, null)) else MaterialTheme.colorScheme.onSurfaceVariant)
        if (oldest != null) Text(
            stringResource(R.string.surfaces_age_minutes, ((now.toULong().coerceAtLeast(oldest) - oldest) / 60_000uL).toLong()),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis,
        )
    }
}

internal data class NextWaitingAction(val entry: AgentEntry, val open: () -> Unit)
internal val LocalNextWaitingAgent = staticCompositionLocalOf<NextWaitingAction?> { null }

@Composable
internal fun NextWaitingAgentButton() {
    val next = LocalNextWaitingAgent.current ?: return
    TextButton(onClick = next.open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        next.entry.terminal.agentStatus?.let { AgentStatusMark(it, modifier = Modifier.size(12.dp)) }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.agents_next_waiting, displayName(next.entry.terminal), next.entry.host.name),
            maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
    }
}
