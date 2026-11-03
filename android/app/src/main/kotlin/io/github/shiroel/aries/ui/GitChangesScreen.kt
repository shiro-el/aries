package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException

/** Local inspection state disappears on close; references only enter the editable Chat draft. */
@Composable
internal fun GitChangesDialog(
    link: HostLink?,
    terminal: TerminalInfo,
    onDismiss: () -> Unit,
    onSend: ((String) -> Unit)?,
) {
    var changes by remember { mutableStateOf<GitChanges?>(null) }
    var file by remember { mutableStateOf<GitFile?>(null) }
    var kind by remember { mutableStateOf(GitDiffKind.UNSTAGED) }
    var diff by remember { mutableStateOf<GitDiff?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var visible by remember(file, kind) { mutableIntStateOf(200) }
    var selection by remember(file, kind) { mutableStateOf<IntRange?>(null) }
    val connection = link?.connection
    LaunchedEffect(connection, revision, file, kind) {
        error = null
        diff = null
        selection = null
        try {
            val conn = connection ?: throw CoreException.Closed()
            if (file == null) {
                changes = null
                changes = conn.gitChanges(terminal.address)
            } else {
                val cwd = changes?.cwd ?: return@LaunchedEffect
                diff = conn.gitDiff(terminal.address, cwd, file!!.path, kind)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoreException) {
            error = e.message
        }
    }
    fun back() { if (file != null) { file = null; selection = null } else onDismiss() }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = ::back)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = ::back) { Text(stringResource(R.string.back_button)) }
                    Text(stringResource(R.string.git_changes_title), Modifier.weight(1f).padding(12.dp))
                    TextButton(onClick = { selection = null; revision++ }) { Text(stringResource(R.string.git_retry_button)) }
                }
                HorizontalDivider()
                changes?.let { Text(it.cwd, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace) }
                error?.let { Text(stringResource(R.string.git_error, it), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                val selectedFile = file
                val list = changes
                if (selectedFile == null) {
                    when {
                        list == null && error == null -> Text(stringResource(R.string.git_loading), Modifier.padding(12.dp))
                        list?.notRepository == true -> Text(stringResource(R.string.git_not_repository), Modifier.padding(12.dp))
                        list != null -> {
                            if (list.truncated) Text(stringResource(R.string.git_truncated), Modifier.padding(12.dp))
                            if (list.files.isEmpty() && !list.truncated) Text(stringResource(R.string.git_clean), Modifier.padding(12.dp))
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(list.files) { _, entry ->
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(entry.path, Modifier.clickable {
                                            kind = when { entry.untracked -> GitDiffKind.UNTRACKED; entry.unstaged -> GitDiffKind.UNSTAGED; else -> GitDiffKind.STAGED }
                                            file = entry
                                        }.padding(vertical = 8.dp), fontFamily = FontFamily.Monospace)
                                        entry.originalPath?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        Row {
                                            fun open(next: GitDiffKind) { kind = next; file = entry }
                                            if (entry.staged) TextButton(onClick = { open(GitDiffKind.STAGED) }) { Text(stringResource(R.string.git_staged)) }
                                            if (entry.unstaged) TextButton(onClick = { open(GitDiffKind.UNSTAGED) }) { Text(stringResource(R.string.git_unstaged)) }
                                            if (entry.untracked) TextButton(onClick = { open(GitDiffKind.UNTRACKED) }) { Text(stringResource(R.string.git_untracked)) }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                } else {
                    Text(selectedFile.path, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
                    Row {
                        if (selectedFile.staged) FilterChip(selected = kind == GitDiffKind.STAGED, onClick = { kind = GitDiffKind.STAGED }, label = { Text(stringResource(R.string.git_staged)) })
                        if (selectedFile.unstaged) FilterChip(selected = kind == GitDiffKind.UNSTAGED, onClick = { kind = GitDiffKind.UNSTAGED }, label = { Text(stringResource(R.string.git_unstaged)) })
                        if (selectedFile.untracked) Text(stringResource(R.string.git_untracked), Modifier.padding(12.dp))
                    }
                    if (onSend != null) {
                        Text(stringResource(R.string.git_select_hint), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                        Row {
                            val path = "${changes?.cwd?.trimEnd('/')}/${selectedFile.path}"
                            TextButton(onClick = { onSend(formatGitReference(path, diff?.lines.orEmpty().take(visible))); onDismiss() }) { Text(stringResource(R.string.git_send_file)) }
                            TextButton(enabled = selection != null && diff != null, onClick = {
                                val range = selection ?: return@TextButton
                                val lines = diff?.lines ?: return@TextButton
                                onSend(formatGitReference(path, lines.slice(range)))
                                onDismiss()
                            }) { Text(stringResource(R.string.git_send_selection)) }
                        }
                    }
                    val patch = diff
                    if (patch == null && error == null) Text(stringResource(R.string.git_loading), Modifier.padding(12.dp))
                    if (patch != null) {
                        GitDiffView(patch, Modifier.weight(1f), visible, { visible += 200 }, selection,
                            if (onSend == null) null else { index ->
                                val first = selection
                                selection = if (first == null || first.first != first.last) index..index else minOf(first.first, index)..maxOf(first.first, index)
                            })
                    }
                }
            }
        }
    }
}
