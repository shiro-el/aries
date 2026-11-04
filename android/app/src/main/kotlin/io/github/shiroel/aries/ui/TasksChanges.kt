package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException

internal data class TaskChangeStats(val added: Int, val removed: Int)

@Composable
internal fun tasksDiffColors(): DiffColors {
    val tokens = LocalLayoutTokens.current
    val palette = if (MaterialTheme.colorScheme.background.luminance() < .5f) tokens.dark else tokens.light
    return palette.diff ?: DiffColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error)
}

@Composable
internal fun tasksChangeText(stats: TaskChangeStats): AnnotatedString {
    val label = stringResource(R.string.tasks_change_stats, stats.added, stats.removed)
    val colors = tasksDiffColors()
    return remember(label, colors) {
        buildAnnotatedString {
            append(label)
            Regex("[+−]\\p{Nd}+").findAll(label).forEach { part ->
                addStyle(SpanStyle(color = if (part.value.startsWith('+')) colors.added else colors.removed),
                    part.range.first, part.range.last + 1)
            }
        }
    }
}

/** Read only on opening Changes. Keep the result across tabs and visits, scoped to this Agent. */
internal class TasksChangesState {
    var changes by mutableStateOf<GitChanges?>(null)
        private set
    var patches by mutableStateOf<Map<Pair<String, GitDiffKind>, GitDiff>>(emptyMap())
        private set
    var stats by mutableStateOf<TaskChangeStats?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Message?>(null)
        private set
    var partial by mutableStateOf(false)
        private set
    private var source: Pair<String?, ULong>? = null
    private var connection: HostConnection? = null
    fun matches(terminal: TerminalInfo) = source == (terminal.currentCwd to terminal.turn)

    suspend fun load(conn: HostConnection, terminal: TerminalInfo, force: Boolean = false) {
        if (!force && matches(terminal) && connection === conn) return
        loading = true
        source = null
        connection = null
        error = null
        stats = null
        partial = false
        patches = emptyMap()
        try {
            val git = conn.gitChanges(terminal.address)
            changes = git
            val result = mutableMapOf<Pair<String, GitDiffKind>, GitDiff>()
            var added = 0
            var removed = 0
            var cachedChars = 0
            var binary = false
            partial = git.truncated
            for (file in git.files) for (kind in file.diffKinds()) {
                val patch = conn.gitDiff(terminal.address, git.cwd, file.path, kind)
                added += patch.lines.count { it.oldLine == null && it.newLine != null }
                removed += patch.lines.count { it.oldLine != null && it.newLine == null }
                partial = partial || patch.truncated
                binary = binary || patch.binary
                // Totals stay cached; cap retained patch text across a large working folder.
                val chars = patch.lines.sumOf { it.text.length }
                if (cachedChars + chars <= 512 * 1024) {
                    result[file.path to kind] = patch
                    cachedChars += chars
                }
            }
            patches = result
            // Truncated responses must never look like exact totals.
            if (!partial && !git.notRepository && !binary) {
                stats = TaskChangeStats(added, removed)
            }
            source = terminal.currentCwd to terminal.turn
            connection = conn
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = errorMessage(e) }
        finally { loading = false }
    }
}

private fun GitFile.diffKinds() = buildList {
    if (staged) add(GitDiffKind.STAGED)
    if (unstaged) add(GitDiffKind.UNSTAGED)
    if (untracked) add(GitDiffKind.UNTRACKED)
}

@Composable
internal fun ColumnScope.TasksChanges(state: TasksChangesState, link: HostLink?, terminal: TerminalInfo, onSend: (String) -> Unit) {
    var revision by remember(state) { mutableIntStateOf(0) }
    var selected by remember(state) { mutableStateOf<String?>(null) }
    val connection = link?.connection
    LaunchedEffect(state, connection, terminal.currentCwd, terminal.turn, revision) {
        if (connection != null) state.load(connection, terminal, force = revision > 0)
    }
    BackHandler(selected != null) { selected = null }
    link?.let { ConnectionNotice(it) }
    val gutter = LocalLayoutTokens.current.contentPadding
    val colors = tasksDiffColors()
    Row(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        state.stats?.let { Text(tasksChangeText(it), Modifier.weight(1f).padding(vertical = 12.dp)) }
        TextButton(onClick = { selected = null; revision++ }, enabled = connection != null && !state.loading) {
            Text(stringResource(R.string.files_refresh_button))
        }
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(stringResource(R.string.git_error, messageText(it)), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
    val git = state.changes
    if (git == null && !state.loading && connection == null) Text(stringResource(R.string.tasks_offline), Modifier.padding(16.dp))
    if (git?.notRepository == true) Text(stringResource(R.string.git_not_repository), Modifier.padding(16.dp))
    if (git?.files?.isEmpty() == true && !git.truncated && !git.notRepository) Text(stringResource(R.string.git_clean), Modifier.padding(16.dp))
    if (state.partial) Text(stringResource(R.string.git_truncated), Modifier.padding(16.dp))
    val file = git?.files?.find { it.path == selected }
    if (file != null) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = gutter)) {
            TextButton(onClick = { selected = null }) { Text(stringResource(R.string.tasks_changes)) }
            Text(file.path, fontFamily = FontFamily.Monospace)
            GitFileDiff(connection, terminal.address, git.cwd, file, revision, wrap = true, onSend = onSend,
                cachedDiffs = state.patches.filterKeys { it.first == file.path }.mapKeys { it.key.second }, colors = colors)
        }
    } else LazyColumn(Modifier.weight(1f).testTag("tasks-changes-files")) {
        items(git?.files.orEmpty(), key = { it.path }) { changed ->
            Row(Modifier.fillMaxWidth().clickable { selected = changed.path }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(changed.status, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Text(changed.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider()
        }
    }
}
