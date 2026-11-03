package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.launch

/** The Host label is authoritative; a rejected write keeps the menu available for retry. */
@Composable
internal fun WatchAgentMenuItem(terminal: TerminalInfo, link: HostLink?, onChanged: (TerminalInfo) -> Unit) {
    if (terminal.agentStatus == null) return
    val watchLabel = stringResource(R.string.agent_watch_label)
    val scope = rememberCoroutineScope()
    var saving by remember(terminal.address) { mutableStateOf(false) }
    var error by remember(terminal.address) { mutableStateOf<String?>(null) }
    fun changeWatch() {
        val conn = link?.connection ?: return
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try { onChanged(conn.setWatched(terminal.address, !terminal.watched)) }
            catch (e: CoreException) { error = e.message ?: e.toString() }
            finally { saving = false }
        }
    }
    DropdownMenuItem(
        text = { Text(watchLabel) },
        leadingIcon = { Checkbox(checked = terminal.watched, onCheckedChange = null) },
        enabled = link?.connection != null && !saving,
        onClick = ::changeWatch,
        modifier = Modifier.testTag("agent-watch").semantics {
            toggleableState = ToggleableState(terminal.watched)
            role = Role.Checkbox
        },
    )
    error?.let { Text(stringResource(R.string.agent_watch_error, it), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
}
