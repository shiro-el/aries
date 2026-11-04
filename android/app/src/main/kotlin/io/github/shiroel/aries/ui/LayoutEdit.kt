package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.LayoutEdit
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LayoutNameKind { NEW_SPACE, NEW_TAB, RENAME_SPACE, RENAME_TAB }

class LayoutNameForm(val kind: LayoutNameKind, val id: String = "", initialName: String = "", folder: String = "") {
    var name by mutableStateOf(initialName)
    var folder by mutableStateOf(folder)
    val creating get() = kind == LayoutNameKind.NEW_SPACE || kind == LayoutNameKind.NEW_TAB
    fun request(): LayoutEdit = when (kind) {
        LayoutNameKind.NEW_SPACE -> LayoutEdit.NewSpace(name, folder.takeIf { it.isNotBlank() })
        LayoutNameKind.NEW_TAB -> LayoutEdit.NewTab(id, name, folder.takeIf { it.isNotBlank() }, null)
        LayoutNameKind.RENAME_SPACE -> LayoutEdit.RenameSpace(id, name)
        LayoutNameKind.RENAME_TAB -> LayoutEdit.RenameTab(id, name)
    }
}

sealed interface LayoutEndTarget {
    data class Space(val id: String) : LayoutEndTarget
    data class Tab(val id: String) : LayoutEndTarget
    data class Terminal(val info: TerminalInfo) : LayoutEndTarget
    data class Pane(val spaceId: String, val tabId: String, val paneId: String, val address: String) : LayoutEndTarget
}

data class LayoutImpact(val name: String, val terminals: List<TerminalInfo>) {
    val working get() = terminals.any { it.agentStatus == AgentStatus.Working }
    val agents get() = terminals.count { it.agentStatus != null }
}

/** Edits are single attempts: reconnect must never replay a destructive action. */
class LayoutEditor(private val browser: HostBrowser, private val scope: CoroutineScope) {
    var available by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var operationError by mutableStateOf<Message?>(null)
    private var availabilityError by mutableStateOf<Message?>(null)
    val error: Message? get() = operationError ?: availabilityError
    private var endTarget by mutableStateOf<LayoutEndTarget?>(null)
    var ending: LayoutEndTarget?
        get() = endTarget
        set(value) { operationError = null; availabilityError = null; endTarget = value }
    private var nameForm by mutableStateOf<LayoutNameForm?>(null)
    var naming: LayoutNameForm?
        get() = nameForm
        set(value) { operationError = null; availabilityError = null; nameForm = value }

    /** Recomputed from the latest layout and status watches while the dialog
     * is open, including pane-less descendants herdr-app will clean up. */
    fun impact(target: LayoutEndTarget): LayoutImpact? {
        fun terminals(panes: List<PaneInfo>) = panes.flatMap { listOfNotNull(it.terminal) + it.subAgents }
            .map(browser::current).distinctBy { it.address }
        return when (target) {
            is LayoutEndTarget.Space -> browser.structure?.spaces?.find { it.id == target.id }?.let {
                LayoutImpact(it.name, terminals(it.tabs.flatMap { tab -> tab.panes }))
            }
            is LayoutEndTarget.Tab -> browser.structure?.spaces?.flatMap { it.tabs }?.find { it.id == target.id }?.let {
                LayoutImpact(it.name, terminals(it.panes))
            }
            is LayoutEndTarget.Terminal -> browser.findTerminal(target.info.address)?.let {
                LayoutImpact(displayName(it), listOf(it))
            }
            is LayoutEndTarget.Pane -> endedPane(target)?.let {
                LayoutImpact(it.terminalAddress, it.subAgents.map(browser::current))
            }
        }
    }

    /** herdr-app addresses Panes by ID alone. Never close an empty or ambiguous ID,
     * or a Pane that acquired a Terminal while the confirmation was open. */
    fun endedPane(target: LayoutEndTarget.Pane): PaneInfo? {
        val spaces = browser.structure?.spaces ?: return null
        if (target.paneId.isBlank() || spaces.flatMap { it.tabs }.flatMap { it.panes }.count { it.id == target.paneId } != 1) return null
        return spaces.find { it.id == target.spaceId }?.tabs?.find { it.id == target.tabId }?.panes
            ?.singleOrNull { it.id == target.paneId && it.terminalAddress == target.address && it.terminal == null && browser.findTerminal(it.terminalAddress) == null }
    }

    fun requireAvailable(): Boolean {
        if (available) return true
        availabilityError = Message(R.string.layout_app_unavailable)
        return false
    }

    fun saveName() {
        val form = naming ?: return
        val conn = browser.link?.connection ?: return
        if (busy || !requireAvailable()) return
        busy = true
        operationError = null
        scope.launch {
            try {
                conn.editLayout(form.request())
                naming = null
            } catch (e: CoreException) {
                operationError = problemFor(e).body
            } finally {
                busy = false
            }
        }
    }

    suspend fun check() {
        val link = browser.link
        val conn = link?.takeIf { it.state == LinkState.Up }?.connection ?: run { available = false; return }
        try {
            available = conn.herdrAppAvailable()
            if (available) availabilityError = null
        } catch (e: CoreException) {
            available = false
            if (link.state == LinkState.Up) availabilityError = problemFor(e).body
        }
    }

    fun end(held: Boolean = false) {
        val target = ending ?: return
        val impact = impact(target) ?: return
        if (impact.working && !held) return
        val conn = browser.link?.connection ?: return
        if (busy || (target !is LayoutEndTarget.Terminal && !requireAvailable())) return
        busy = true
        operationError = null
        scope.launch {
            try {
                when (target) {
                    is LayoutEndTarget.Terminal -> conn.killTerminal(target.info.address)
                    is LayoutEndTarget.Space -> conn.editLayout(LayoutEdit.CloseSpace(target.id))
                    is LayoutEndTarget.Tab -> conn.editLayout(LayoutEdit.CloseTab(target.id))
                    is LayoutEndTarget.Pane -> conn.editLayout(LayoutEdit.ClosePane(target.paneId))
                }
                ending = null
            } catch (e: CoreException) {
                operationError = problemFor(e).body
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun LayoutEditControls(browser: HostBrowser, editor: LayoutEditor) {
    val link = browser.link
    val connection = if (link?.state == LinkState.Up) link.connection else null
    val enabled = connection != null && !editor.busy
    LaunchedEffect(connection) {
        while (isActive) {
            editor.check()
            delay(10_000)
        }
    }
    editor.error?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
    editor.naming?.let { form ->
        AlertDialog(
            onDismissRequest = { if (!editor.busy) editor.naming = null },
            title = { Text(stringResource(when (form.kind) {
                LayoutNameKind.NEW_SPACE -> R.string.layout_new_space_button
                LayoutNameKind.NEW_TAB -> R.string.layout_new_tab_button
                LayoutNameKind.RENAME_SPACE -> R.string.layout_rename_space_button
                LayoutNameKind.RENAME_TAB -> R.string.layout_rename_tab_button
            })) },
            text = { Column {
                ConsoleField(value = form.name, onValueChange = { form.name = it }, enabled = !editor.busy,
                    modifier = Modifier.testTag("layout-name"), label = { Text(stringResource(R.string.layout_name_label)) })
                if (form.creating) ConsoleField(value = form.folder, onValueChange = { form.folder = it }, enabled = !editor.busy,
                    modifier = Modifier.testTag("layout-folder"), label = { Text(stringResource(R.string.layout_folder_label)) })
                editor.error?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = editor::saveName, enabled = enabled) { Text(stringResource(R.string.layout_save_button)) } },
            dismissButton = { TextButton(onClick = { editor.naming = null }, enabled = !editor.busy) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
    editor.ending?.let { target ->
        val impact = editor.impact(target)
        val working = impact?.working == true
        val canEnd = connection != null && !editor.busy && impact != null &&
            (target is LayoutEndTarget.Terminal || editor.available)
        AlertDialog(
            onDismissRequest = { if (!editor.busy) editor.ending = null },
            title = { Text(stringResource(R.string.layout_confirm_title, impact?.name.orEmpty())) },
            text = { Column {
                Text(stringResource(R.string.layout_impact, impact?.terminals?.size ?: 0, impact?.agents ?: 0))
                if (impact == null) Text(stringResource(R.string.layout_target_gone))
                if (working) Text(stringResource(R.string.layout_working_warning), color = MaterialTheme.colorScheme.error)
                editor.error?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = {
                if (working) HoldToEnd(target to impact.terminals.map { it.address to it.agentStatus }, canEnd) { editor.end(held = true) }
                else TextButton(onClick = { editor.end() }, enabled = canEnd) { Text(stringResource(R.string.layout_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { editor.ending = null }, enabled = !editor.busy) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
}

/** No click/long-click semantics can bypass the full two-second press. A
 * release, drag out of bounds, cancellation or changed target resets it. */
@Composable
private fun HoldToEnd(target: Any, enabled: Boolean, onHeld: () -> Unit) {
    val complete by rememberUpdatedState(onHeld)
    Box(Modifier.semantics { role = Role.Button }
        .background(MaterialTheme.colorScheme.errorContainer)
        .heightIn(min = 48.dp)
        .pointerInput(target, enabled) {
            if (enabled) awaitEachGesture {
                awaitFirstDown().consume()
                val released = withTimeoutOrNull(2_000) {
                    waitForUpOrCancellation()?.consume()
                    true
                }
                if (released == null) complete()
            }
        }.padding(16.dp)) {
        Text(stringResource(R.string.layout_hold_button), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
internal fun EndTerminalButton(editor: LayoutEditor, terminal: TerminalInfo, onOpen: () -> Unit = {}) {
    val label = stringResource(R.string.layout_kill_label, displayName(terminal))
    MenuDivider()
    ActionMenuItem(stringResource(R.string.layout_kill_button), R.drawable.ic_x,
        destructive = true, onClick = { onOpen(); editor.ending = LayoutEndTarget.Terminal(terminal) }, enabled = !editor.busy,
        modifier = Modifier.semantics { contentDescription = label })
}
