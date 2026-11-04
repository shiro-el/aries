package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
            ActionIcon(R.drawable.ic_file, R.string.git_send_file, { onSend(formatGitReference(path, diff?.lines.orEmpty())) })
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
        GitDiffView(patch, Modifier.weight(1f), selection,
            if (onSend == null) null else { index ->
                val first = selection
                selection = if (first == null || first.first != first.last) index..index else minOf(first.first, index)..maxOf(first.first, index)
            })
    }
}
