package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** The list selects a Host without leaving or replacing its live connections. */
@Composable
internal fun AgentActions(board: AgentBoard) {
    val access = LocalAgentCreation.current ?: return
    val history = LocalCreationHostHistory.current ?: rememberCreationHostHistory()
    var hostId by remember(board) { mutableStateOf<String?>(null) }
    var choosing by remember(board) { mutableStateOf(false) }
    val host = board.hosts.find { it.id == hostId }
        ?: board.hosts.firstOrNull { board.links[it.id]?.state == LinkState.Up && board.closed[it.id] != true }
        ?: board.hosts.firstOrNull()
    val selectedId = host?.id
    val link = board.links[selectedId]
    val enabled = link?.state == LinkState.Up && board.closed[selectedId] != true
    CreateActions(buttonEnabled = board.hosts.isNotEmpty(), creationEnabled = enabled,
        savedLaunches = board.savedLaunches[selectedId].orEmpty(), sheetTag = "agents-create-sheet",
        onOpen = {
            hostId = board.hosts.find { it.id == history.hostId }?.id
            choosing = false
        },
        onNewTask = { if (enabled) access.startTask(link) },
        onRunSaved = { saved ->
            if (enabled) { history.used(link.host.id); access.runSaved(board, link, saved) }
        },
        heading = {
            if (board.hosts.size > 1) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.creation_host_label), style = MaterialTheme.typography.labelMedium)
                Box {
                    ConsoleButton(onClick = { choosing = true }, modifier = Modifier.testTag("creation-host")) {
                        Text(host?.name.orEmpty(), Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        board.hosts.forEach { candidate ->
                            DropdownMenuItem(text = { Text(candidate.name) },
                                modifier = Modifier.testTag("creation-host:${candidate.id}"),
                                onClick = { hostId = candidate.id; choosing = false })
                        }
                    }
                }
            }
            if (!enabled && host != null) Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.creation_host_disconnected, host.name),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val problem = (link?.state as? LinkState.Down)?.why?.body
                Text(if (problem != null) stringResource(problem.res, *problem.args.toTypedArray())
                    else stringResource(R.string.reconnecting),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
}
