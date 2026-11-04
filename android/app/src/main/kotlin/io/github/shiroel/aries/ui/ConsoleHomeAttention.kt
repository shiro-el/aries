package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CallUrgency

internal val CallEntry.needsUrgentAttention: Boolean
    get() = needsAttention &&
        call.urgency in listOf(CallUrgency.URGENT, CallUrgency.EMERGENCY)

/** The Host tree is local; this compact strip keeps other Hosts' urgent Calls in sight. */
@Composable
internal fun ConsoleHomeAttention(model: HostsModel, selectedHost: String? = null) {
    val urgent = model.calls.calls.filter { it.host.id != selectedHost && it.needsUrgentAttention }
        .sortedByDescending { it.call.urgency == CallUrgency.EMERGENCY }
    if (urgent.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.calls_title), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = model::openCallInbox) { Text(stringResource(R.string.call_banner_open)) }
        }
        urgent.take(2).forEach { entry ->
            val accent = if (entry.call.urgency == CallUrgency.EMERGENCY) MaterialTheme.colorScheme.error
                else statusColor(io.github.shiroel.aries.core.AgentStatus.Blocked(null, null))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("console-urgent:${entry.key}")
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.call_open_chat)) { model.openCallInbox(); model.openCallChat(entry) }
                .padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(urgencyLabel(entry.call.urgency), color = accent, style = MaterialTheme.typography.labelMedium)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.call.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.call_inbox_caller, model.callCallerName(entry) ?: entry.call.terminal.orEmpty(), entry.host.name),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    HorizontalDivider()
}
