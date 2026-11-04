package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

internal data class TaskChangeStats(val added: Int, val removed: Int, val trackedOnly: Boolean = false)

@Composable
internal fun tasksDiffColors(): DiffColors {
    val tokens = LocalLayoutTokens.current
    val palette = if (MaterialTheme.colorScheme.background.luminance() < .5f) tokens.dark else tokens.light
    return palette.diff ?: DiffColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error)
}

@Composable
internal fun tasksChangeText(stats: TaskChangeStats): AnnotatedString {
    val label = stringResource(if (stats.trackedOnly) R.string.tasks_tracked_change_stats else R.string.tasks_change_stats, stats.added, stats.removed)
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

/** Read only on opening Changes. Results and the selected file belong to this Agent's owner. */
internal class TasksChangesState {
    var changes by mutableStateOf<GitChanges?>(null)
        private set
    var stats by mutableStateOf<TaskChangeStats?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Message?>(null)
        private set
    var partial by mutableStateOf(false)
        private set
    var fetchedAt by mutableStateOf<Long?>(null)
        private set
    var selected by mutableStateOf<String?>(null)

    private data class Context(val cwd: String?, val turn: ULong, val working: Boolean)
    private fun context(terminal: TerminalInfo) = Context(terminal.currentCwd, terminal.turn, terminal.agentStatus == AgentStatus.Working)
    private var observed: Context? = null
    private var source: Context? = null
    private var connection: HostConnection? = null
    private var generation = 0L
    private var complete by mutableStateOf(false)

    fun matches(terminal: TerminalInfo, conn: HostConnection?) = complete && currentResult(terminal, conn)
    fun currentResult(terminal: TerminalInfo, conn: HostConnection?) =
        changes != null && source == context(terminal) && connection === conn && conn != null
    fun previousFolder(terminal: TerminalInfo) = changes != null && source?.cwd != terminal.currentCwd

    /** Also called by the existing status feed while Changes is closed. */
    fun updateTerminal(terminal: TerminalInfo) {
        val next = context(terminal)
        if (observed == next) return
        val folderChanged = observed != null && observed?.cwd != next.cwd
        observed = next
        invalidate(keepPreviousFolder = folderChanged)
        if (folderChanged) selected = null
    }

    fun observe(conn: HostConnection?, terminal: TerminalInfo) {
        updateTerminal(terminal)
        if (connection !== conn) {
            connection = conn
            invalidate()
        }
    }

    private fun invalidate(keepPreviousFolder: Boolean = false) {
        generation++
        complete = false
        loading = false
        stats = null
        error = null
        partial = false
        if (!keepPreviousFolder) { changes = null; fetchedAt = null }
    }

    suspend fun load(conn: HostConnection, terminal: TerminalInfo, force: Boolean = false) {
        observe(conn, terminal)
        if (!force && matches(terminal, conn)) return
        val request = ++generation
        val requestedContext = context(terminal)
        complete = false
        loading = true
        error = null
        stats = null
        partial = false
        // Keep a prior folder's list only as an explicitly disabled placeholder.
        if (!previousFolder(terminal)) changes = null
        try {
            val git = conn.gitChanges(terminal.address)
            currentCoroutineContext().ensureActive()
            if (generation != request) return
            changes = git
            source = requestedContext
            fetchedAt = System.currentTimeMillis()
            partial = git.truncated
            if (!git.notRepository) {
                val totals = conn.gitStats(terminal.address, git.cwd)
                currentCoroutineContext().ensureActive()
                if (generation != request) return
                partial = partial || totals.truncated
                if (!partial && !totals.binary) stats = TaskChangeStats(totals.added.toInt(), totals.removed.toInt(),
                    trackedOnly = git.files.any { it.untracked })
            }
            complete = true
            if (git.files.none { it.path == selected }) selected = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == request) error = errorMessage(e) }
        finally { if (generation == request) loading = false }
    }
}

@Composable
internal fun ColumnScope.TasksChanges(state: TasksChangesState, link: HostLink?, terminal: TerminalInfo, onSend: (String) -> Unit) {
    var revision by remember(state) { mutableIntStateOf(0) }
    val connection = link?.connections?.collectAsState()?.value
    LaunchedEffect(state, connection, terminal.currentCwd, terminal.turn, terminal.agentStatus == AgentStatus.Working, revision) {
        state.observe(connection, terminal)
        if (connection != null) state.load(connection, terminal, force = revision > 0)
    }
    BackHandler(state.selected != null) { state.selected = null }
    link?.let { ConnectionNotice(it) }
    val gutter = LocalLayoutTokens.current.contentPadding
    val colors = tasksDiffColors()
    Row(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        state.stats?.takeIf { state.matches(terminal, connection) }?.let { Text(tasksChangeText(it), Modifier.weight(1f).padding(vertical = 12.dp)) }
        TextButton(onClick = { state.selected = null; revision++ }, enabled = connection != null && !state.loading) {
            Text(stringResource(R.string.files_refresh_button))
        }
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(stringResource(R.string.git_error, messageText(it)), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
    val previousFolder = state.previousFolder(terminal)
    val current = state.currentResult(terminal, connection)
    val git = state.changes.takeIf { current || previousFolder }
    if (previousFolder) Text(stringResource(R.string.tasks_changes_previous_folder), Modifier.padding(16.dp))
    if (current && terminal.agentStatus == AgentStatus.Working) state.fetchedAt?.let { fetched ->
        val now by produceState(System.currentTimeMillis(), fetched) {
            while (true) { delay(30_000); value = System.currentTimeMillis() }
        }
        Text(stringResource(R.string.tasks_changes_working_age, relativeAge(LocalContext.current, fetched.toULong(), now)),
            Modifier.padding(horizontal = gutter, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
    if (git == null && !state.loading && connection == null) Text(stringResource(R.string.tasks_offline), Modifier.padding(16.dp))
    if (current && git?.notRepository == true) Text(stringResource(R.string.git_not_repository), Modifier.padding(16.dp))
    if (current && git?.files?.isEmpty() == true && !git.truncated && !git.notRepository) Text(stringResource(R.string.git_clean), Modifier.padding(16.dp))
    if (state.partial) Text(stringResource(R.string.git_truncated), Modifier.padding(16.dp))
    val file = git?.files?.find { current && it.path == state.selected }
    if (file != null) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = gutter)) {
            TextButton(onClick = { state.selected = null }) { Text(stringResource(R.string.tasks_changes)) }
            Text(file.path, fontFamily = FontFamily.Monospace)
            GitFileDiff(connection, terminal.address, git.cwd, file, revision, wrap = true, onSend = onSend,
                colors = colors)
        }
    } else LazyColumn(Modifier.weight(1f).testTag("tasks-changes-files")) {
        items(git?.files.orEmpty(), key = { it.path }) { changed ->
            val label = stringResource(R.string.git_file_accessibility_label, gitStatusDescription(changed.status), changed.path)
            Row(Modifier.fillMaxWidth().clickable(enabled = current, role = Role.Button) { state.selected = changed.path }
                .semantics { contentDescription = label }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(changed.status, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Text(changed.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun gitStatusDescription(status: String): String {
    val labels = when (status) {
        "??" -> listOf(R.string.git_untracked)
        "!!" -> listOf(R.string.git_status_changed)
        "DD", "AU", "UD", "UA", "DU", "AA", "UU" -> listOf(R.string.git_status_conflicted)
        else -> status.filterNot(Char::isWhitespace).map { code -> when (code) {
            'M', 'T' -> R.string.git_status_modified
            'A' -> R.string.git_status_added
            'D' -> R.string.git_status_deleted
            'R' -> R.string.git_status_renamed
            'C' -> R.string.git_status_copied
            else -> R.string.git_status_changed
        } }.distinct().ifEmpty { listOf(R.string.git_status_changed) }
    }
    return labels.map { stringResource(it) }.joinToString(", ")
}
