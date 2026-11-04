package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentPreset

/** Collapsed home entry expands into the retained NewTask form; no independent launch/send path. */
@Composable
internal fun TasksNewTaskCard(model: HostsModel, board: AgentBoard) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var hostId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    val host = board.hosts.find { it.id == hostId }
        ?: model.task?.link?.host?.takeIf { h -> h in board.hosts }
        ?: board.hosts.firstOrNull { board.links[it.id]?.state == LinkState.Up }
        ?: board.hosts.firstOrNull()
    val task = model.task?.takeIf { it.link.host.id == host?.id }
    val link = host?.let { board.links[it.id] }
    LaunchedEffect(expanded, host?.id, link?.state, task) {
        if (expanded && task == null) host?.let { board.links[it.id] }?.let { model.prepareTask(it, showDialog = false) }
    }
    val editable = task == null || (!task.form.busy && task.terminal == null && !task.launchUnknown && !task.deliveryUnknown)
    val expandLabel = stringResource(R.string.tasks_expand_label)
    val open: (Boolean) -> Unit = { full ->
        if (link != null) model.prepareTask(link, showDialog = full)
        expanded = true
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("tasks-new-card"),
        shape = RoundedCornerShape(LocalLayoutTokens.current.cornerRadius),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (expanded) expanded = false else open(false) },
                    modifier = Modifier.weight(1f).testTag("tasks-new-task")) {
                    Icon(painterResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_plus), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (expanded) LocalLayoutScreens.current.newTaskTitle else R.string.tasks_prompt_hint), Modifier.weight(1f), maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { open(true) }, enabled = host != null,
                    modifier = Modifier.testTag("tasks-new-expand")) {
                    Icon(painterResource(R.drawable.ic_expand), expandLabel, Modifier.size(20.dp))
                }
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        AssistChip(onClick = { menu = "host" }, enabled = editable,
                            modifier = Modifier.pickerDescription(stringResource(R.string.creation_host_label), host?.name.orEmpty()),
                            label = { Text(host?.name ?: stringResource(R.string.hosts_title)) })
                        DropdownMenu(expanded = menu == "host", onDismissRequest = { menu = null }) {
                            board.hosts.forEach { h -> DropdownMenuItem(text = { Text(h.name) },
                                enabled = board.links[h.id]?.state == LinkState.Up,
                                onClick = {
                                    hostId = h.id
                                    board.links[h.id]?.let { model.prepareTask(it, showDialog = false) }
                                    menu = null
                                }) }
                        }
                    }
                    Box {
                        AssistChip(onClick = { menu = "folder" }, enabled = task != null && editable,
                            modifier = Modifier.pickerDescription(stringResource(R.string.launch_cwd), task?.form?.cwd.orEmpty()),
                            label = { Text(task?.form?.cwd?.takeIf(String::isNotBlank)?.let { it.substringAfterLast('/').ifBlank { it } }
                                ?: stringResource(R.string.launch_cwd), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp)) })
                        DropdownMenu(expanded = menu == "folder", onDismissRequest = { menu = null }) {
                            val folders = (board.structureOf(host?.id.orEmpty())?.spaces.orEmpty().mapNotNull { it.folder } +
                                task?.form?.recentCwds.orEmpty()).distinct()
                            folders.forEach { path -> DropdownMenuItem(text = { Text(path) }, onClick = { task?.form?.cwd = path; menu = null }) }
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_task_browse)) }, onClick = { menu = null; open(true) })
                        }
                    }
                    Box {
                        AssistChip(onClick = { menu = "agent" }, enabled = task != null && editable,
                            modifier = Modifier.pickerDescription(stringResource(R.string.tasks_agent), task?.form?.preset?.let { stringResource(it.label) }.orEmpty()),
                            label = { Text(task?.form?.preset?.let { stringResource(it.label) } ?: stringResource(R.string.tasks_agent)) })
                        DropdownMenu(expanded = menu == "agent", onDismissRequest = { menu = null }) {
                            listOf(AgentPreset.CLAUDE_CODE, AgentPreset.CODEX).forEach { preset ->
                                DropdownMenuItem(text = { Text(stringResource(preset.label)) }, onClick = { task?.form?.preset = preset; menu = null })
                            }
                        }
                    }
                }
                if (host != null && link?.state != LinkState.Up) TasksHostConnection(host, link, showHost = false, modifier = Modifier)
                if (task != null) {
                    OutlinedTextField(task.input, { task.input = it }, enabled = !task.form.busy && !task.sent,
                        placeholder = { Text(stringResource(R.string.tasks_prompt_hint)) }, minLines = 1, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("tasks-new-prompt"))
                    NewTaskRecovery(task, model)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val starting = stringResource(R.string.new_task_starting)
                        if (task.form.busy) LinearProgressIndicator(Modifier.weight(1f).semantics { contentDescription = starting })
                        else Spacer(Modifier.weight(1f))
                        TextButton(onClick = model::startInlineTask, enabled = task.canStart,
                            modifier = Modifier.testTag("tasks-new-start")) { Text(stringResource(LocalLayoutScreens.current.startTaskLabel)) }
                        if (task.terminal != null) TextButton(onClick = model::openCreatedTask) {
                            Text(stringResource(LocalLayoutScreens.current.openTaskLabel))
                        }
                    }
                } else if (host == null) {
                    TextButton(onClick = LocalTasksHosts.current) { Text(stringResource(R.string.agents_empty_hosts_button)) }
                } else if (link?.state == LinkState.Up) {
                    Text(stringResource(R.string.tasks_preparing), Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
