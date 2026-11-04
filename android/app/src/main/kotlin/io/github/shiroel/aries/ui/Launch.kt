package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentPreset
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.core.SavedLaunch
import io.github.shiroel.aries.core.SpaceInfo

/** How each Agent preset is labelled in the form. */
val AgentPreset.label: Int
    get() = when (this) {
        AgentPreset.CLAUDE_CODE -> R.string.preset_claude_code
        AgentPreset.CODEX -> R.string.preset_codex
        AgentPreset.OPEN_CODE -> R.string.preset_opencode
        AgentPreset.PI -> R.string.preset_pi
    }

/**
 * The "new Terminal" form: an Agent preset (with extra arguments) or a
 * command line, a cwd (typed, the Space's folder, or a recent one) and the
 * Space/Tab to put it in. Starts on the Space and Tab the phone is looking
 * at. It also saves, edits and deletes the Host's named launch configs.
 * There is no first prompt.
 */
class LaunchForm(
    private val structure: HostStructure,
    val recentCwds: List<String>,
    spaceId: String?,
    tabId: String?,
    saved: List<SavedLaunch> = emptyList(),
) {
    /** Without a preset, [command] is a command line; with one, the Agent's extra arguments. */
    private var selectedPreset by mutableStateOf<AgentPreset?>(null)
    var preset: AgentPreset?
        get() = selectedPreset
        set(value) {
            if (selectedPreset != value) {
                model = ""
                effort = ""
            }
            selectedPreset = value
        }
    var model by mutableStateOf("")
    var effort by mutableStateOf("")
    var command by mutableStateOf("")
    var cwd by mutableStateOf("")

    /** The Host's saved launch configs. */
    val saved = mutableStateListOf<SavedLaunch>().apply { addAll(saved) }

    /** Name to save the form under, and which saved config it edits (null: a new one). */
    var name by mutableStateOf("")
    var editingId by mutableStateOf<String?>(null)
        private set
    var spaceId by mutableStateOf(spaceId?.takeIf { id -> structure.spaces.any { it.id == id } })
        private set
    var tabId by mutableStateOf(tabId?.takeIf { id -> space?.tabs?.any { it.id == id } == true } ?: space?.tabs?.firstOrNull()?.id)
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf<Message?>(null)
    var launchUnknown by mutableStateOf(false)
    var removedLaunch by mutableStateOf<SavedLaunch?>(null)

    val spaces: List<SpaceInfo> get() = structure.spaces
    val space: SpaceInfo? get() = structure.spaces.find { it.id == spaceId }

    fun selectSpace(id: String?) {
        spaceId = id
        tabId = space?.tabs?.firstOrNull()?.id
    }

    fun selectTab(id: String) {
        tabId = id
    }

    val canLaunch: Boolean get() = (preset != null || command.isNotBlank()) && !busy && !launchUnknown

    val canSave: Boolean get() = canLaunch && name.isNotBlank()

    fun request() = LaunchRequest(
        command = command.trim(),
        cwd = cwd.trim().ifEmpty { null },
        spaceId = spaceId,
        tabId = spaceId?.let { tabId },
        preset = preset,
        model = preset?.let { model.trim().ifEmpty { null } },
        effort = preset?.takeUnless { it == AgentPreset.OPEN_CODE }?.let { effort.ifEmpty { null } },
    )

    /** Loads [launch] into the form; saving then replaces it. */
    fun edit(launch: SavedLaunch) {
        fill(launch.request)
        name = launch.name
        editingId = launch.id
    }

    /** Puts [request] in the form (a Space or Tab that no longer exists is dropped). */
    fun fill(request: LaunchRequest) {
        preset = request.preset
        model = request.model ?: ""
        effort = request.effort ?: ""
        command = request.command
        cwd = request.cwd ?: ""
        selectSpace(request.spaceId?.takeIf { id -> structure.spaces.any { it.id == id } })
        request.tabId?.takeIf { id -> space?.tabs?.any { it.id == id } == true }?.let { tabId = it }
    }

    fun toSaved() = SavedLaunch(editingId ?: "", name.trim(), request())

    /** [launch] was stored: show it in the list and keep editing it. */
    fun stored(launch: SavedLaunch) {
        val i = saved.indexOfFirst { it.id == launch.id }
        if (i >= 0) saved[i] = launch else saved += launch
        name = launch.name
        editingId = launch.id
    }

    fun removed(id: String) {
        saved.removeAll { it.id == id }
        if (editingId == id) editingId = null
    }
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
fun LaunchDialog(
    form: LaunchForm,
    onLaunch: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit = {},
    onDelete: (SavedLaunch) -> Unit = {},
    onPin: ((SavedLaunch) -> Unit)? = null,
    onUndoDelete: () -> Unit = {},
    onLaunchAgain: () -> Unit = {},
    onStopTracking: () -> Unit = {},
    connected: Boolean = true,
) {
    var confirmAgain by remember(form) { mutableStateOf(false) }
    if (confirmAgain) AlertDialog(
        onDismissRequest = { confirmAgain = false },
        title = { Text(stringResource(R.string.launch_unknown_title)) },
        text = { Text(stringResource(R.string.launch_unknown_body)) },
        confirmButton = { TextButton(onClick = { confirmAgain = false; onLaunchAgain() }) {
            Text(stringResource(R.string.launch_again_button))
        } },
        dismissButton = { TextButton(onClick = { confirmAgain = false }) { Text(stringResource(R.string.cancel_button)) } },
    )
    AlertDialog(
        modifier = Modifier.testTag("launch-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.launch_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (form.busy) {
                    Text(stringResource(R.string.launch_starting))
                    ConsoleProgress(Modifier.fillMaxWidth())
                }
                if (form.launchUnknown && !form.busy) {
                    Text(stringResource(R.string.launch_unknown_body), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.launch_stop_tracking_body))
                    TextButton(onClick = onStopTracking) { Text(stringResource(R.string.launch_stop_tracking_button)) }
                    TextButton(onClick = { confirmAgain = true }, enabled = connected) { Text(stringResource(R.string.launch_review_button)) }
                }
                form.removedLaunch?.let { removed ->
                    Text(stringResource(R.string.launch_removed, removed.name))
                    TextButton(onClick = onUndoDelete, enabled = !form.busy) { Text(stringResource(R.string.launch_undo_button)) }
                }
                if (form.saved.isNotEmpty()) {
                    Text(stringResource(R.string.launch_saved), style = MaterialTheme.typography.labelLarge)
                    for (s in form.saved) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, Modifier.weight(1f))
                            ActionIcon(R.drawable.ic_square_pen, R.string.launch_saved_edit,
                                { form.edit(s) }, enabled = !form.busy && !form.launchUnknown, modifier = Modifier.testTag("saved_edit:" + s.id))
                            ActionIcon(R.drawable.ic_x, R.string.launch_saved_delete,
                                { onDelete(s) }, enabled = !form.busy, modifier = Modifier.testTag("saved_delete:" + s.id))
                            onPin?.let { pin ->
                                ActionIcon(R.drawable.agent_pin, R.string.shortcuts_pin_button, { pin(s) }, enabled = !form.busy)
                            }
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "custom") {
                        ConsoleChoice(
                            selected = form.preset == null,
                            onClick = { form.preset = null }, enabled = !form.busy && !form.launchUnknown,
                            label = { Text(stringResource(R.string.preset_custom)) },
                        )
                    }
                    items(AgentPreset.entries, key = { "preset:" + it.name }) { p ->
                        ConsoleChoice(
                            modifier = Modifier.testTag("preset:" + p.name),
                            selected = form.preset == p,
                            onClick = { form.preset = p }, enabled = !form.busy && !form.launchUnknown,
                            label = { Text(stringResource(p.label)) },
                        )
                    }
                }
                form.preset?.let { preset -> LaunchOptions(form, preset, enabled = !form.busy && !form.launchUnknown) }
                ConsoleField(
                    form.command, { form.command = it }, enabled = !form.busy && !form.launchUnknown,
                    modifier = Modifier.testTag("launch_command"),
                    label = {
                        Text(stringResource(if (form.preset == null) R.string.launch_command else R.string.launch_preset_args))
                    },
                    singleLine = true,
                )
                ConsoleField(
                    form.cwd, { form.cwd = it }, enabled = !form.busy && !form.launchUnknown,
                    label = { Text(stringResource(R.string.launch_cwd)) },
                    placeholder = { Text(stringResource(R.string.launch_cwd_default)) },
                    singleLine = true,
                )
                val folder = form.space?.folder
                if (folder != null || form.recentCwds.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (folder != null) {
                            item(key = "folder") {
                                ConsoleChoice(enabled = !form.busy && !form.launchUnknown, selected = form.cwd == folder, onClick = { form.cwd = folder }) {
                                    Text(stringResource(R.string.launch_cwd_space_folder, folder))
                                }
                            }
                        }
                        items(form.recentCwds.filter { it != folder }, key = { "recent:$it" }) { dir ->
                            ConsoleChoice(enabled = !form.busy && !form.launchUnknown, selected = form.cwd == dir, onClick = { form.cwd = dir }) { Text(dir) }
                        }
                    }
                }
                Text(stringResource(R.string.launch_space), style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "none") {
                        ConsoleChoice(
                            selected = form.spaceId == null,
                            onClick = { form.selectSpace(null) }, enabled = !form.busy && !form.launchUnknown,
                            label = { Text(stringResource(R.string.launch_space_none)) },
                        )
                    }
                    items(form.spaces, key = { "space:" + it.id }) { s ->
                        ConsoleChoice(
                            modifier = Modifier.testTag("launch_space:" + s.id),
                            selected = s.id == form.spaceId,
                            onClick = { form.selectSpace(s.id) }, enabled = !form.busy && !form.launchUnknown,
                            label = { Text(s.name) },
                        )
                    }
                }
                val space = form.space
                if (space != null && space.tabs.isNotEmpty()) {
                    Text(stringResource(R.string.launch_tab), style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(space.tabs, key = { "tab:" + it.id }) { t ->
                            ConsoleChoice(
                                modifier = Modifier.testTag("launch_tab:" + t.id),
                                selected = t.id == form.tabId,
                                onClick = { form.selectTab(t.id) }, enabled = !form.busy && !form.launchUnknown,
                                label = { Text(t.name) },
                            )
                        }
                    }
                }
                ConsoleField(
                    form.name, { form.name = it }, enabled = !form.busy,
                    modifier = Modifier.testTag("launch_name"),
                    label = { Text(stringResource(R.string.launch_save_name)) },
                    singleLine = true,
                )
                TextButton(enabled = form.canSave, onClick = onSave) {
                    Text(stringResource(R.string.launch_save_button))
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
            TextButton(enabled = form.canLaunch && connected, onClick = onLaunch) { Text(stringResource(R.string.launch_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } },
    )
}
