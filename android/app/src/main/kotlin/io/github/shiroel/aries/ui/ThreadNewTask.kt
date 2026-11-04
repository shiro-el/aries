package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*

/** The empty conversation uses the same retained NewTask launch/readiness/send owner as every entry. */
@Composable
internal fun ThreadNewTaskEntry(model: HostsModel, board: AgentBoard) {
    val host = model.task?.link?.host?.takeIf { it in board.hosts }
        ?: board.hosts.firstOrNull { board.links[it.id]?.state == LinkState.Up }
        ?: board.hosts.firstOrNull()
    LaunchedEffect(host, board.links[host?.id]?.state) {
        if (host != null && model.task == null && board.links[host.id]?.state == LinkState.Up)
            model.beginThreadTask(host, showDialog = false)
    }
    val task = model.task?.takeIf { it.link.host == host }
    if (task != null) ThreadTaskForm(task, model, model::startInlineTask, model::openCreatedTask)
    else Column(Modifier.fillMaxWidth().padding(16.dp)) {
        ConsoleButton(onClick = { if (host == null) model.startAddingHost() else model.beginThreadTask(host) },
            modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (host == null) R.string.console_add_host else R.string.new_task_title))
        }
    }
}

@Composable
internal fun ThreadNewTaskScreen(task: NewTask, onStart: () -> Unit, onOpenChat: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("new-task-dialog"), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                val formHeight = maxHeight * .7f
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.back_button)) }
                        Text(stringResource(R.string.new_task_title), style = MaterialTheme.typography.titleLarge)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.thread_new_body), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.heightIn(max = formHeight).verticalScroll(rememberScrollState())) {
                        ThreadTaskForm(task, LocalTaskOwner.current, onStart, onOpenChat)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadTaskForm(task: NewTask, model: HostsModel?, onStart: () -> Unit, onOpenChat: () -> Unit) {
    val form = task.form
    var picker by remember(task) { mutableStateOf<String?>(null) }
    var browsing by remember(task) { mutableStateOf(false) }
    var options by remember(task) { mutableStateOf(false) }
    val editable = !form.busy && task.terminal == null && !task.launchUnknown && !task.deliveryUnknown
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { picker = "host" }, enabled = editable && model != null,
                label = { Text(task.link.host.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            AssistChip(onClick = { picker = "folder" }, enabled = editable,
                label = { Text(form.cwd.substringAfterLast('/').ifBlank { stringResource(R.string.launch_cwd) },
                    modifier = Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) })
            AssistChip(onClick = { picker = "agent" }, enabled = editable,
                label = { Text(form.preset?.let { stringResource(it.label) } ?: stringResource(R.string.new_task_title)) })
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                OutlinedTextField(task.input, { model?.interactWithHome(); task.input = it }, enabled = !form.busy && !task.sent,
                    placeholder = { Text(stringResource(R.string.new_task_prompt)) }, minLines = 2, maxLines = 6,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) model?.interactWithHome() }
                        .testTag("new-task-prompt"), shape = RoundedCornerShape(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { options = !options }, enabled = editable) { Text(stringResource(R.string.new_task_options)) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onStart, enabled = task.canStart, modifier = Modifier.testTag("thread-task-start")) {
                        Icon(painterResource(R.drawable.ic_send), stringResource(if (task.terminal == null) R.string.new_task_start else R.string.new_task_wait_again))
                    }
                }
            }
        }
        if (options) {
            OutlinedTextField(form.cwd, { form.cwd = it }, enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.launch_cwd)) }, modifier = Modifier.fillMaxWidth().testTag("launch_cwd"))
            val saved = form.saved.filter { it.request.preset in listOf(AgentPreset.CLAUDE_CODE, AgentPreset.CODEX) }
            if (saved.isNotEmpty()) TextButton(onClick = { picker = "saved" }, enabled = editable) { Text(stringResource(R.string.launch_saved)) }
            form.preset?.let { LaunchOptions(form, it) }
            OutlinedTextField(form.command, { form.command = it }, enabled = editable,
                label = { Text(stringResource(R.string.launch_preset_args)) }, modifier = Modifier.fillMaxWidth())
        }
        if (task.waiting) Text(stringResource(R.string.new_task_waiting), style = MaterialTheme.typography.bodySmall)
        if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        form.error?.let { Text(messageText(it), color = MaterialTheme.colorScheme.error) }
        if (task.terminal != null && !form.busy) TextButton(onClick = onOpenChat) { Text(stringResource(R.string.new_task_open_chat)) }
    }
    if (picker != null) ModalBottomSheet(onDismissRequest = { picker = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (picker) {
                "host" -> model?.hosts?.forEach { host -> TextButton(onClick = {
                    picker = null
                    if (host.id != task.link.host.id) {
                        model.beginThreadTask(host, showDialog = model.taskVisible, resetFolder = true)
                    }
                }) { Text(host.name) } }
                "folder" -> {
                    (form.spaces.mapNotNull { it.folder } + form.recentCwds).distinct().forEach { path ->
                        TextButton(onClick = { form.cwd = path; picker = null }) { Text(path) }
                    }
                    TextButton(onClick = { picker = null; browsing = true }) { Text(stringResource(R.string.new_task_browse)) }
                }
                "agent" -> listOf(AgentPreset.CLAUDE_CODE, AgentPreset.CODEX).forEach { preset ->
                    TextButton(onClick = { form.preset = preset; picker = null }) { Text(stringResource(preset.label)) }
                }
                "saved" -> form.saved.filter { it.request.preset in listOf(AgentPreset.CLAUDE_CODE, AgentPreset.CODEX) }.forEach { saved ->
                    TextButton(onClick = { form.fill(saved.request); form.selectSpace(null); picker = null }) { Text(saved.name) }
                }
            }
        }
    }
    if (browsing) TaskFolderDialog(task.link.connection, form.cwd.ifBlank { "/" },
        onSelect = { form.cwd = it; browsing = false }, onDismiss = { browsing = false })
}
