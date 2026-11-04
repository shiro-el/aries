package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class TaskIssue(val repository: GithubRepository, val item: GithubItem)
internal data class AgentCreationAccess(
    val startTask: (HostLink) -> Unit,
    val runSaved: (AgentBoard, HostLink, SavedLaunch) -> Unit,
)
internal val LocalAgentCreation = compositionLocalOf<AgentCreationAccess?> { null }
internal val LocalTaskOwner = staticCompositionLocalOf<HostsModel?> { null }
internal val LocalStartTask = compositionLocalOf<((TaskIssue?) -> Unit)?> { null }

/** A launch is performed once. Only a positive readiness observation permits the first send.
 * Reads may be retried, writes never are: a lost write reply is an uncertain outcome. */
internal class NewTask(var link: HostLink, val form: LaunchForm, prompt: String) {
    var input by mutableStateOf(TextFieldValue(prompt, TextRange(prompt.length)))
    val prompt: String get() = input.text
    var terminal by mutableStateOf<TerminalInfo?>(null)
        private set
    var deliveryUnknown by mutableStateOf(false)
        private set
    var launchUnknown by mutableStateOf(false)
        private set
    var sent by mutableStateOf(false)
        private set
    var waiting by mutableStateOf(false)
        private set

    val canStart get() = link.state == LinkState.Up && !form.busy && !deliveryUnknown && !launchUnknown && !sent &&
        prompt.isNotBlank() && form.cwd.startsWith('/') && form.preset in taskPresets

    suspend fun start(onReady: (TerminalInfo, String) -> Unit) {
        if (!canStart) return
        form.busy = true
        form.error = null
        try {
            val launchConnection = link.connection ?: throw CoreException.Closed()
            if (terminal == null) {
                // Without an idempotency token a disconnected launch cannot safely be repeated.
                launchUnknown = true
                terminal = launchConnection.launch(form.request())
                launchUnknown = false
            }
            waiting = true
            val ready = withTimeoutOrNull(60_000) {
                link.keep { conn, _ ->
                    val watch = conn.watchTerminals()
                    try {
                        var ready: TerminalInfo? = null
                        while (ready == null) {
                            val current = watch.next().find { it.address == terminal!!.address }
                            if (current != null) {
                                terminal = current
                                if (current.state != "live" || current.agentStatus is AgentStatus.Blocked || current.agentStatus is AgentStatus.Error) break
                                if (current.agentStatus == AgentStatus.Idle || current.agentStatus == AgentStatus.Done) ready = current
                            }
                        }
                        ready?.let { it to conn }
                    } finally { withContext(NonCancellable) { watch.stop(); watch.close() } }
                }
            }
            waiting = false
            if (ready == null) {
                form.error = Message(R.string.new_task_not_ready)
                return
            }
            val (terminal, conn) = ready
            val text = prompt
            deliveryUnknown = true
            when (conn.sendMessage(terminal.address, text, object : SendListener { override fun onHeld() {} })) {
                SendOutcome.Sent -> {
                    sent = true
                    deliveryUnknown = false
                    onReady(terminal, "")
                }
                is SendOutcome.Blocked -> {
                    deliveryUnknown = false
                    form.error = Message(R.string.new_task_not_ready)
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: CoreException) {
            if (e is CoreException.Rpc && e.kind == "spawn_failed" && terminal == null) launchUnknown = false
            form.error = when {
                deliveryUnknown -> Message(R.string.new_task_delivery_unknown)
                launchUnknown -> Message(R.string.new_task_launch_unknown)
                else -> launchProblem(e)
            }
        } finally { form.busy = false; waiting = false }
    }
}

private val taskPresets = listOf(AgentPreset.CLAUDE_CODE, AgentPreset.CODEX)

/** Shared task flow for Host and Agent lists, the connected Host sheet and GitHub Issues. */
@Composable
internal fun NewTaskAccess(model: HostsModel, content: @Composable () -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val history = rememberCreationHostHistory()
    val currentHostId = when (val screen = model.screen) {
        is Screen.Browse -> screen.browser.host.id
        is Screen.Chat -> screen.chat.host.id
        is Screen.Terminal -> screen.viewer.link?.host?.id
        else -> null
    }
    LaunchedEffect(currentHostId) { currentHostId?.let(history::used) }
    val startOnLink: (HostLink, TaskIssue?) -> Unit = start@{ link, issue ->
        if (issue?.repository?.on != null) return@start
        history.used(link.host.id)
        val prompt = issue?.let {
            val repo = it.repository
            val body = it.item.body.replace(Regex("\\s+"), " ").trim()
            val summary = body.take(2000) + if (body.length > 2000) "…" else ""
            resources.getString(R.string.new_task_issue_prompt, repo.nameWithOwner, it.item.number.toInt(),
                it.item.title, "https://${repo.hostname}/${repo.nameWithOwner}/issues/${it.item.number}", summary)
        }
        model.prepareTask(link, issue?.repository?.cwd, prompt)
    }
    val start: (TaskIssue?) -> Unit = start@{ issue ->
        val link = when (val screen = model.screen) {
            is Screen.Browse -> screen.browser.link
            is Screen.Chat -> screen.chat.link
            is Screen.Terminal -> screen.viewer.link
            else -> null
        } ?: return@start
        startOnLink(link, issue)
    }
    val browse = model.screen as? Screen.Browse
    LaunchedEffect(model.newTaskHostId, browse?.browser?.structure) {
        if (model.newTaskHostId != null && browse?.browser?.host?.id == model.newTaskHostId && browse?.browser?.structure != null) {
            model.newTaskHostId = null
            start(null)
        }
    }
    CompositionLocalProvider(LocalTaskOwner provides model, LocalShowWorktrees provides model::showWorktrees,
        LocalStartTask provides start,
        LocalCreationHostHistory provides history,
        LocalAgentCreation provides AgentCreationAccess({ startOnLink(it, null) }, model::runSavedFromAgents)) {
        content()
        model.activeWorktree?.let { flow ->
            WorktreeDialog(flow.form, enabled = flow.browser.link?.state == LinkState.Up && !flow.browser.closed,
                onReload = model::loadWorktrees, onOpen = { model.openWorktree() },
                onDismiss = model::dismissWorktrees, onOpenAgain = { model.openWorktree(again = true) })
        }
        model.activeLaunch?.let { flow ->
            val context = androidx.compose.ui.platform.LocalContext.current
            LaunchDialog(flow.form,
                onLaunch = { model.launchActive() }, onDismiss = model::dismissLaunch,
                onSave = model::saveActiveLaunch, onDelete = model::deleteActiveLaunch,
                connected = flow.link.state == LinkState.Up,
                onUndoDelete = model::undoLaunchDelete, onLaunchAgain = { model.launchActive(again = true) },
                onPin = if (io.github.shiroel.aries.LaunchShortcuts.canPin(context)) { saved ->
                    io.github.shiroel.aries.LaunchShortcuts.pin(context, flow.link.host.id, saved)
                } else null)
        }
        model.task?.takeIf { model.taskVisible }?.let { current ->
            LocalLayoutScreens.current.NewTask(current,
                onStart = model::startTask,
                onOpenChat = model::openCreatedTask,
                onDismiss = model::dismissTask)
        }
    }
}

@Composable
internal fun NewTaskDialog(task: NewTask, onStart: () -> Unit, onOpenChat: () -> Unit, onDismiss: () -> Unit,
    fullScreen: Boolean = false) {
    val form = task.form
    var browsing by remember(task) { mutableStateOf(false) }
    var options by remember(task) { mutableStateOf(false) }
    var savedOpen by remember(task) { mutableStateOf(false) }
    val editable = !form.busy && task.terminal == null && !task.launchUnknown
    val promptField: @Composable () -> Unit = {
        OutlinedTextField(task.input, { task.input = it }, enabled = !form.busy && !task.sent,
            minLines = 4, maxLines = 8, label = { Text(stringResource(R.string.new_task_prompt)) },
            modifier = Modifier.fillMaxWidth().testTag("new-task-prompt"))
    }
    val fields: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalLayoutTokens.current.itemSpacing)) {
            if (fullScreen) promptField()
            Text(task.link.host.name, style = MaterialTheme.typography.labelLarge)
            val saved = form.saved.filter { it.request.preset in taskPresets }
            if (saved.isNotEmpty()) Box {
                TextButton(onClick = { savedOpen = true }, enabled = editable) { Text(stringResource(R.string.launch_saved)) }
                DropdownMenu(expanded = savedOpen, onDismissRequest = { savedOpen = false }) {
                    saved.forEach { launch -> DropdownMenuItem(text = { Text(launch.name) }, modifier = Modifier.testTag("new-task-saved:${launch.id}"), onClick = {
                        form.fill(launch.request)
                        // New tasks create an unplaced Terminal; never reuse a saved layout target.
                        form.selectSpace(null)
                        savedOpen = false
                    }) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                taskPresets.forEach { preset ->
                    FilterChip(selected = form.preset == preset, enabled = editable,
                        onClick = { form.preset = preset }, label = { Text(stringResource(preset.label)) })
                }
            }
            OutlinedTextField(form.cwd, { form.cwd = it }, enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.launch_cwd)) },
                supportingText = { Text(stringResource(R.string.new_task_folder_hint)) },
                isError = form.cwd.isNotBlank() && !form.cwd.startsWith('/'),
                modifier = Modifier.testTag("launch_cwd"))
            TextButton(onClick = { browsing = true }, enabled = editable && task.link.connection != null) {
                Text(stringResource(R.string.new_task_browse))
            }
            if (!fullScreen) promptField()
            TextButton(onClick = { options = !options }, enabled = editable) { Text(stringResource(R.string.new_task_options)) }
            if (options && editable) {
                form.preset?.let { LaunchOptions(form, it) }
                OutlinedTextField(form.command, { form.command = it }, enabled = editable,
                    singleLine = true, label = { Text(stringResource(R.string.launch_preset_args)) })
            }
            if (task.waiting) Text(stringResource(R.string.new_task_waiting))
            if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            form.error?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
            if (task.terminal != null && !form.busy) TextButton(onClick = onOpenChat) { Text(stringResource(R.string.new_task_open_chat)) }
        }
    }
    val start: @Composable () -> Unit = {
        ConsoleButton(onClick = onStart, enabled = task.canStart, modifier = if (fullScreen) Modifier.fillMaxWidth() else Modifier) {
            Text(stringResource(if (task.terminal == null) R.string.new_task_start else R.string.new_task_wait_again))
        }
    }
    if (fullScreen) androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("new-task-dialog"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
                    Text(stringResource(R.string.new_task_title), style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).padding(LocalLayoutTokens.current.contentPadding)) { fields() }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(LocalLayoutTokens.current.contentPadding)) { start() }
            }
        }
    } else AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("new-task-dialog"),
        title = { Text(stringResource(R.string.new_task_title)) }, text = fields,
        confirmButton = { TextButton(onClick = onStart, enabled = task.canStart) {
            Text(stringResource(if (task.terminal == null) R.string.new_task_start else R.string.new_task_wait_again))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } })
    if (browsing) TaskFolderDialog(task.link.connection, form.cwd.ifBlank { "/" },
        onSelect = { form.cwd = it; browsing = false }, onDismiss = { browsing = false })
}

/** Explicit paths use list_files on the Host without needing an existing Terminal. */
@Composable
internal fun TaskFolderDialog(connection: HostConnection?, initial: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(initial) }
    var directory by remember { mutableStateOf<RemoteDirectory?>(null) }
    var error by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(connection, path, revision) {
        directory = null
        error = false
        try { directory = connection?.listFiles("", path) ?: throw CoreException.Closed()
        } catch (e: CancellationException) { throw e
        } catch (e: CoreException) { error = true }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.new_task_browse)) }, text = {
        Column {
            Text(directory?.path ?: path)
            Row {
                TextButton(onClick = { path = path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" } }, enabled = path != "/") {
                    Text(stringResource(R.string.files_parent_folder_button))
                }
                TextButton(onClick = { revision++ }) { Text(stringResource(R.string.github_refresh)) }
            }
            if (error) Text(stringResource(R.string.files_failed), color = MaterialTheme.colorScheme.error)
            else if (directory == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(directory?.entries.orEmpty().filter { it.directory }, key = { it.name }) { entry ->
                    TextButton(onClick = { path = directory!!.path.trimEnd('/') + "/" + entry.name }) { Text(entry.name) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { directory?.path?.let(onSelect) }, enabled = directory != null) {
        Text(stringResource(R.string.new_task_use_folder))
    } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } })
}
