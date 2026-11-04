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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentPreset
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.WorktreeInfo
import io.github.shiroel.aries.core.WorktreeOpen
import kotlinx.coroutines.launch

private class WorktreeForm(val cwd: String, val spaceId: String?) {
    var entries by mutableStateOf<List<WorktreeInfo>>(emptyList())
    var selected by mutableStateOf<WorktreeInfo?>(null)
    var preset by mutableStateOf<AgentPreset?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<Message?>(null)
}

/** Uses the phone's focused Terminal, never the desktop's current Space.
 * Nested-machine folders must not accidentally be executed on the Host. */
@Composable
internal fun WorktreeControls(browser: HostBrowser, unplaced: TerminalInfo? = null, asMenuItem: Boolean = false) {
    val scope = rememberCoroutineScope()
    var form by remember(browser) { mutableStateOf<WorktreeForm?>(null) }
    val space = browser.openSpace
    if (space == null && unplaced == null) return
    val tab = browser.tab
    val pane = tab?.panes?.find { it.id == browser.focusedPane(tab) } ?: tab?.panes?.firstOrNull()
    val terminal = unplaced ?: pane?.terminal?.let(browser::current)
    val cwd = if (terminal?.machine != null) null else terminal?.currentCwd ?: space?.folder
    val connection = browser.link?.takeIf { it.state == LinkState.Up }?.connection
    fun load(target: WorktreeForm) {
        val conn = connection ?: return
        if (target.busy) return
        target.busy = true
        target.error = null
        scope.launch {
            try {
                target.entries = conn.listWorktrees(target.cwd)
                target.selected = null
            } catch (e: CoreException) {
                target.error = problemFor(e).body
            } finally {
                target.busy = false
            }
        }
    }
    fun openWorktrees() {
        val target = WorktreeForm(cwd ?: return, space?.id)
        form = target
        load(target)
    }
    val enabled = cwd != null && connection != null && !browser.closed
    val label = stringResource(R.string.worktrees_button)
    if (asMenuItem) ActionMenuItem(label, R.drawable.ic_folder,
        modifier = Modifier.semantics { contentDescription = label }, enabled = enabled, onClick = ::openWorktrees)
    else ActionIcon(R.drawable.ic_folder, R.string.worktrees_button, enabled = enabled, onClick = ::openWorktrees)
    form?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!target.busy) form = null },
            title = { Text(stringResource(R.string.worktrees_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (target.busy) Text(stringResource(R.string.worktrees_loading))
                else if (target.entries.isEmpty() && target.error == null) Text(stringResource(R.string.worktrees_empty))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(target.entries, key = { it.path }) { entry ->
                        SingleChoiceRow(
                            selected = target.selected == entry,
                            onClick = { target.selected = entry },
                            modifier = Modifier.fillMaxWidth().testTag("worktree:" + entry.path),
                            enabled = !target.busy && !entry.bare && entry.prunable == null,
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
                        ConsoleChoice(target.preset == null, { target.preset = null }, enabled = !target.busy,
                            label = { Text(stringResource(R.string.worktrees_shell)) })
                    }
                    items(AgentPreset.entries) { preset ->
                        ConsoleChoice(target.preset == preset, { target.preset = preset },
                            modifier = Modifier.testTag("worktree-preset:" + preset.name), enabled = !target.busy,
                            label = { Text(stringResource(preset.label)) })
                    }
                }
                target.error?.let { Text(stringResource(it.res, *it.args.toTypedArray()), color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = !target.busy && connection != null, onClick = { load(target) }) {
                    Text(stringResource(R.string.worktrees_refresh_button))
                }
            } },
            confirmButton = { TextButton(enabled = !target.busy && target.selected != null && connection != null, onClick = {
                val conn = connection ?: return@TextButton
                val selected = target.selected ?: return@TextButton
                if (target.busy) return@TextButton
                target.busy = true
                target.error = null
                scope.launch {
                    try {
                        when (val opened = conn.openWorktree(selected.path, target.spaceId, target.preset)) {
                            is WorktreeOpen.Tab -> browser.reveal(opened.spaceId, opened.tabId, opened.address)
                            is WorktreeOpen.Terminal -> browser.reveal(null, null, opened.address)
                        }
                        form = null
                    } catch (e: CoreException) {
                        target.error = problemFor(e).body
                    } finally {
                        target.busy = false
                    }
                }
            }) { Text(stringResource(R.string.worktrees_open_button)) } },
            dismissButton = { TextButton(enabled = !target.busy, onClick = { form = null }) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}
