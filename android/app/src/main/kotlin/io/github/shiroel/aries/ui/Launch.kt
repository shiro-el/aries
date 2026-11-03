package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.core.SpaceInfo

/**
 * The "new Terminal" form: a command line, a cwd (typed, the Space's folder,
 * or a recent one) and the Space/Tab to put it in. Starts on the Space and
 * Tab the phone is looking at.
 */
class LaunchForm(
    private val structure: HostStructure,
    val recentCwds: List<String>,
    spaceId: String?,
    tabId: String?,
) {
    var command by mutableStateOf("")
    var cwd by mutableStateOf("")
    var spaceId by mutableStateOf(spaceId?.takeIf { id -> structure.spaces.any { it.id == id } })
        private set
    var tabId by mutableStateOf(tabId?.takeIf { id -> space?.tabs?.any { it.id == id } == true } ?: space?.tabs?.firstOrNull()?.id)
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf<Message?>(null)

    val spaces: List<SpaceInfo> get() = structure.spaces
    val space: SpaceInfo? get() = structure.spaces.find { it.id == spaceId }

    fun selectSpace(id: String?) {
        spaceId = id
        tabId = space?.tabs?.firstOrNull()?.id
    }

    fun selectTab(id: String) {
        tabId = id
    }

    val canLaunch: Boolean get() = command.isNotBlank() && !busy

    fun request() = LaunchRequest(
        command = command.trim(),
        cwd = cwd.trim().ifEmpty { null },
        spaceId = spaceId,
        tabId = spaceId?.let { tabId },
    )
}

/** Why a launch failed, for the form. */
fun launchProblem(e: CoreException): Message = when (e) {
    is CoreException.Rpc -> when (e.kind) {
        "spawn_failed" -> Message(R.string.launch_spawn_failed, listOf(e.detail))
        else -> Message(R.string.launch_rpc_failed, listOf(e.kind, e.detail))
    }
    is CoreException.Closed -> Message(R.string.launch_connection_closed)
    else -> Message(R.string.connect_other, listOf(e.message ?: ""))
}

@Composable
fun LaunchDialog(form: LaunchForm, onLaunch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.launch_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.command, { form.command = it },
                    label = { Text(stringResource(R.string.launch_command)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    form.cwd, { form.cwd = it },
                    label = { Text(stringResource(R.string.launch_cwd)) },
                    placeholder = { Text(stringResource(R.string.launch_cwd_default)) },
                    singleLine = true,
                )
                val folder = form.space?.folder
                if (folder != null || form.recentCwds.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (folder != null) {
                            item(key = "folder") {
                                AssistChip(
                                    onClick = { form.cwd = folder },
                                    label = { Text(stringResource(R.string.launch_cwd_space_folder, folder)) },
                                )
                            }
                        }
                        items(form.recentCwds.filter { it != folder }, key = { "recent:$it" }) { dir ->
                            AssistChip(onClick = { form.cwd = dir }, label = { Text(dir) })
                        }
                    }
                }
                Text(stringResource(R.string.launch_space), style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "none") {
                        FilterChip(
                            selected = form.spaceId == null,
                            onClick = { form.selectSpace(null) },
                            label = { Text(stringResource(R.string.launch_space_none)) },
                        )
                    }
                    items(form.spaces, key = { "space:" + it.id }) { s ->
                        FilterChip(
                            modifier = Modifier.testTag("launch_space:" + s.id),
                            selected = s.id == form.spaceId,
                            onClick = { form.selectSpace(s.id) },
                            label = { Text(s.name.ifBlank { stringResource(R.string.space_unnamed) }) },
                        )
                    }
                }
                val space = form.space
                if (space != null && space.tabs.isNotEmpty()) {
                    Text(stringResource(R.string.launch_tab), style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(space.tabs, key = { "tab:" + it.id }) { t ->
                            FilterChip(
                                modifier = Modifier.testTag("launch_tab:" + t.id),
                                selected = t.id == form.tabId,
                                onClick = { form.selectTab(t.id) },
                                label = { Text(t.name.ifBlank { stringResource(R.string.tab_unnamed) }) },
                            )
                        }
                    }
                }
                form.error?.let {
                    Text(
                        stringResource(it.res, *it.args.toTypedArray()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = form.canLaunch, onClick = onLaunch) { Text(stringResource(R.string.launch_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } },
    )
}
