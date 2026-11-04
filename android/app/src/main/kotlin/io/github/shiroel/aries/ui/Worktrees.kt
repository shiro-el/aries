package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentPreset
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.WorktreeInfo

internal class WorktreeForm(val cwd: String, val spaceId: String?) {
    var entries by mutableStateOf<List<WorktreeInfo>>(emptyList())
    var selected by mutableStateOf<WorktreeInfo?>(null)
    var preset by mutableStateOf<AgentPreset?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<Message?>(null)
    var openUnknown by mutableStateOf(false)
    var openedAddress: String? = null
}

internal val LocalShowWorktrees = androidx.compose.runtime.compositionLocalOf<
    ((HostBrowser, String, String?) -> Unit)?
> { null }

/** Row actions use that Terminal; Host actions use the selected Space's folder.
 * Nested-machine folders must not accidentally be executed on the Host. */
@Composable
internal fun WorktreeControls(browser: HostBrowser, unplaced: TerminalInfo? = null, asMenuItem: Boolean = false,
    action: (@Composable (enabled: Boolean, onOpen: () -> Unit) -> Unit)? = null,
) {
    val space = if (unplaced == null) browser.openSpace else browser.structure?.spaces?.find { space ->
        space.tabs.any { tab -> tab.panes.any { pane ->
            pane.terminalAddress == unplaced.address || pane.subAgents.any { it.address == unplaced.address }
        } }
    }
    if (space == null && unplaced == null && action == null) return
    val terminal = unplaced?.let(browser::current)
    val cwd = if (terminal?.machine != null) null else terminal?.currentCwd ?: space?.folder
    val show = LocalShowWorktrees.current
    val connection = browser.link?.takeIf { it.state == LinkState.Up }?.connection
    fun openWorktrees() { cwd?.let { show?.invoke(browser, it, space?.id) } }
    val enabled = cwd != null && connection != null && !browser.closed
    val label = stringResource(R.string.worktrees_button)
    if (action != null) action(enabled, ::openWorktrees)
    else if (asMenuItem) ActionMenuItem(label, R.drawable.ic_folder,
        modifier = Modifier.semantics { contentDescription = label }, enabled = enabled, onClick = ::openWorktrees)
    else ActionIcon(R.drawable.ic_folder, R.string.worktrees_button, enabled = enabled, onClick = ::openWorktrees)
}

@Composable
internal fun WorktreeDialog(target: WorktreeForm, enabled: Boolean, onReload: () -> Unit,
    onOpen: () -> Unit, onDismiss: () -> Unit, onOpenAgain: () -> Unit,
) {
    var confirmAgain by remember(target) { mutableStateOf(false) }
    if (confirmAgain) AlertDialog(onDismissRequest = { confirmAgain = false },
        title = { Text(stringResource(R.string.launch_unknown_title)) },
        text = { Text(stringResource(R.string.launch_unknown_body)) },
        confirmButton = { TextButton(onClick = { confirmAgain = false; onOpenAgain() }) { Text(stringResource(R.string.launch_again_button)) } },
        dismissButton = { TextButton(onClick = { confirmAgain = false }) { Text(stringResource(R.string.cancel_button)) } })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.worktrees_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (target.openUnknown && !target.busy) {
                Text(stringResource(R.string.launch_unknown_body), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { confirmAgain = true }, enabled = enabled) { Text(stringResource(R.string.launch_review_button)) }
            }
            if (target.busy) Text(stringResource(R.string.worktrees_loading))
            else if (target.entries.isEmpty() && target.error == null) Text(stringResource(R.string.worktrees_empty))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(target.entries, key = { it.path }) { entry ->
                    SingleChoiceRow(
                        selected = target.selected == entry,
                        onClick = { target.selected = entry },
                        modifier = Modifier.fillMaxWidth().testTag("worktree:" + entry.path),
                        enabled = !target.busy && !target.openUnknown && !entry.bare && entry.prunable == null,
                    ) { Column {
                        Text(entry.path)
                        Text(when {
                            entry.bare -> stringResource(R.string.worktrees_bare)
                            entry.detached -> stringResource(R.string.worktrees_detached, entry.head.orEmpty().take(12))
                            else -> entry.branch?.removePrefix("refs/heads/").orEmpty()
                        }, style = MaterialTheme.typography.bodySmall)
                        if (entry.locked != null) Text(stringResource(R.string.worktrees_locked, entry.locked.orEmpty()), style = MaterialTheme.typography.bodySmall)
                        entry.prunable?.let { Text(stringResource(R.string.worktrees_prunable, it), style = MaterialTheme.typography.bodySmall) }
                    } }
                }
            }
            Text(stringResource(R.string.worktrees_program_label), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ConsoleChoice(target.preset == null, { target.preset = null }, enabled = !target.busy && !target.openUnknown,
                        label = { Text(stringResource(R.string.worktrees_shell)) })
                }
                items(AgentPreset.entries) { preset ->
                    ConsoleChoice(target.preset == preset, { target.preset = preset },
                        modifier = Modifier.testTag("worktree-preset:" + preset.name), enabled = !target.busy && !target.openUnknown,
                        label = { Text(stringResource(preset.label)) })
                }
            }
            target.error?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = !target.busy && enabled, onClick = onReload) {
                Text(stringResource(R.string.worktrees_refresh_button))
            }
        } },
        confirmButton = { TextButton(enabled = !target.busy && !target.openUnknown && target.selected != null && enabled,
            onClick = onOpen) { Text(stringResource(R.string.worktrees_open_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } },
    )
}
