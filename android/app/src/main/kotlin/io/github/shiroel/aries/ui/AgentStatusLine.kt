package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.TerminalInfo

/**
 * A Terminal's Agent status, with the reason and message when Blocked or
 * Error. Blocked and Error are marked as needing the user. A program that
 * does not report status gets no line at all.
 */
@Composable
fun AgentStatusLine(t: TerminalInfo) {
    val status = t.agentStatus ?: return
    val color = statusColor(status)
    val label = agentStatusLabel(status)
    val message = when (status) {
        is AgentStatus.Blocked -> status.message
        is AgentStatus.Error -> status.message
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelLarge)
        if (t.needsAttention) {
            Text(stringResource(R.string.agent_attention_label), color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
    message?.let { Text(it, color = color, style = MaterialTheme.typography.bodySmall) }
}

/** The string resource naming [status] (with the Blocked reason). */
fun agentStatusLabel(status: AgentStatus): Int = when (status) {
    AgentStatus.Working -> R.string.agent_status_working
    is AgentStatus.Blocked -> when (status.reason) {
        BlockedReason.PERMISSION -> R.string.agent_status_blocked_permission
        BlockedReason.QUESTION -> R.string.agent_status_blocked_question
        BlockedReason.AUTH -> R.string.agent_status_blocked_auth
        null -> R.string.agent_status_blocked
    }
    is AgentStatus.Error -> R.string.agent_status_error
    AgentStatus.Done -> R.string.agent_status_done
    AgentStatus.Idle -> R.string.agent_status_idle
}
