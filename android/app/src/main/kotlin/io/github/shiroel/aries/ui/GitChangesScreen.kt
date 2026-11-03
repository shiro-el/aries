package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException

/** Shared diff body inside Files; reads the existing Git API and only returns draft text. */
@Composable
internal fun ColumnScope.GitFileDiff(
    connection: HostConnection?,
    address: String,
    cwd: String,
    selectedFile: GitFile,
    revision: Int,
    onSend: ((String) -> Unit)?,
) {
    var kind by remember(selectedFile) { mutableStateOf(when {
        selectedFile.untracked -> GitDiffKind.UNTRACKED
        selectedFile.unstaged -> GitDiffKind.UNSTAGED
        else -> GitDiffKind.STAGED
    }) }
    var diff by remember(selectedFile, kind) { mutableStateOf<GitDiff?>(null) }
    var error by remember(selectedFile, kind) { mutableStateOf<String?>(null) }
    var visible by remember(selectedFile, kind) { mutableIntStateOf(200) }
    var selection by remember(selectedFile, kind) { mutableStateOf<IntRange?>(null) }
    LaunchedEffect(connection, address, cwd, selectedFile, kind, revision) {
        diff = null
        error = null
        selection = null
        try {
            diff = (connection ?: throw CoreException.Closed()).gitDiff(address, cwd, selectedFile.path, kind)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    error?.let { Text(stringResource(R.string.git_error, it), color = MaterialTheme.colorScheme.error) }
    Row {
        if (selectedFile.staged) FilterChip(selected = kind == GitDiffKind.STAGED, onClick = { kind = GitDiffKind.STAGED }, label = { Text(stringResource(R.string.git_staged)) })
        if (selectedFile.unstaged) FilterChip(selected = kind == GitDiffKind.UNSTAGED, onClick = { kind = GitDiffKind.UNSTAGED }, label = { Text(stringResource(R.string.git_unstaged)) })
        if (selectedFile.untracked) Text(stringResource(R.string.git_untracked), Modifier.padding(12.dp))
    }
    if (onSend != null) {
        Row {
            val path = "${cwd.trimEnd('/')}/${selectedFile.path}"
            ActionIcon(R.drawable.ic_file, R.string.git_send_file, { onSend(formatGitReference(path, diff?.lines.orEmpty().take(visible))) })
            ActionIcon(R.drawable.ic_send, R.string.git_send_selection, enabled = selection != null && diff != null, onClick = {
                val range = selection ?: return@ActionIcon
                val lines = diff?.lines ?: return@ActionIcon
                onSend(formatGitReference(path, lines.slice(range)))
            })
        }
    }
    val patch = diff
    if (patch == null && error == null) Text(stringResource(R.string.git_loading), Modifier.padding(12.dp))
    if (patch != null) {
        if (patch.truncated) Text(stringResource(R.string.git_truncated), Modifier.padding(12.dp))
        if (patch.binary) Text(stringResource(R.string.git_binary), Modifier.padding(12.dp))
        if (patch.lines.isEmpty() && !patch.binary) Text(stringResource(R.string.git_empty_diff), Modifier.padding(12.dp))
        val horizontal = rememberScrollState()
        LazyColumn(Modifier.weight(1f).horizontalScroll(horizontal).testTag("git-diff-lines")) {
            itemsIndexed(patch.lines.take(visible)) { index, line ->
                val selected = selection?.contains(index) == true
                val label = stringResource(R.string.git_line_label, line.oldLine?.toString() ?: "–", line.newLine?.toString() ?: "–", line.text)
                Row(Modifier.fillMaxWidth().selectable(selected, enabled = onSend != null && (line.oldLine != null || line.newLine != null), onClick = {
                    val first = selection
                    selection = if (first == null || first.first != first.last) index..index else minOf(first.first, index)..maxOf(first.first, index)
                }).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .semantics { contentDescription = label }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("${line.oldLine ?: ""} ${line.newLine ?: ""}", Modifier.widthIn(min = 64.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) // i18n-ok: numeric old/new line columns
                    Text(line.text, fontFamily = FontFamily.Monospace, color = when {
                        line.newLine != null && line.oldLine == null -> MaterialTheme.colorScheme.primary
                        line.oldLine != null && line.newLine == null -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    })
                }
            }
            if (patch.lines.size > visible) item {
                TextButton(onClick = { visible += 200 }) { Text(stringResource(R.string.git_more_button)) }
            }
        }
    }
}
