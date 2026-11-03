package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.launch

/** The Host label is authoritative; a rejected write leaves the switch unchanged. */
@Composable
internal fun WatchAgentToggle(terminal: TerminalInfo, link: HostLink?, onChanged: (TerminalInfo) -> Unit) {
    if (terminal.agentStatus == null) return
    val scope = rememberCoroutineScope()
    var saving by remember(terminal.address) { mutableStateOf(false) }
    var error by remember(terminal.address) { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.agent_watch_label), style = MaterialTheme.typography.labelMedium)
            Switch(checked = terminal.watched, enabled = link?.connection != null && !saving,
                modifier = Modifier.testTag("agent-watch"), onCheckedChange = { watched ->
                    val conn = link?.connection ?: return@Switch
                    saving = true
                    error = null
                    scope.launch {
                        try { onChanged(conn.setWatched(terminal.address, watched)) }
                        catch (e: CoreException) { error = e.message ?: e.toString() }
                        finally { saving = false }
                    }
                })
        }
        error?.let { Text(stringResource(R.string.agent_watch_error, it), color = MaterialTheme.colorScheme.error) }
    }
}
