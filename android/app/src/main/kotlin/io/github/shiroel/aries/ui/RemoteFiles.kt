package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.GitChanges
import io.github.shiroel.aries.core.GitFile
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.RemoteDirectory
import io.github.shiroel.aries.core.RemoteFilePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One page at a time: no unbounded accumulation of file text in the phone. */
@Composable
fun RemoteFilesScreen(
    connection: HostConnection?,
    address: String,
    onClose: () -> Unit,
    onSend: ((String) -> Unit)? = null,
    initialLocation: FileLocation? = null,
    onDiffSend: ((String) -> Unit)? = onSend,
) {
    val initialFolder = initialLocation?.path?.substringBeforeLast('/')?.ifEmpty { "/" }
    var folder by remember(address, initialLocation) { mutableStateOf(initialFolder) }
    var startingFolder by remember(address, initialLocation) { mutableStateOf(initialFolder) }
    var directory by remember(address, initialLocation) { mutableStateOf<RemoteDirectory?>(null) }
    var file by remember(address, initialLocation) { mutableStateOf(initialLocation?.path) }
    var offsets by remember(address, initialLocation) { mutableStateOf(listOf(initialLocation?.line ?: 1uL)) }
    var page by remember(address) { mutableStateOf<RemoteFilePage?>(null) }
    var wrap by remember(address) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var notText by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var unavailableLine by remember { mutableStateOf<ULong?>(null) }
    var changes by remember(connection, address) { mutableStateOf<GitChanges?>(null) }
    var loadedRevision by remember(connection, address) { mutableIntStateOf(-1) }
    val requestedRevision = retry
    val changesLoaded = loadedRevision == requestedRevision
    var gitError by remember(connection, address) { mutableStateOf<String?>(null) }
    var fullFile by remember(address, file) { mutableStateOf(false) }
    val changedFile = changes?.let { git -> git.files.find { gitFilePath(git.cwd, it.path) == file?.let { path -> java.nio.file.Paths.get(path).normalize().toString() } } }
    var preview by remember(address, file) { mutableStateOf(initialLocation?.path != file) }
    val markupFile = file?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT) in setOf("md", "mmd", "mermaid")
    val mediaFile = remoteMediaKind(file.orEmpty()) != null
    val showingDiff = changedFile != null && !fullFile && !mediaFile
    val showingMarkup = markupFile && preview && !showingDiff
    val scope = rememberCoroutineScope()
    var subfolders by remember(address, directory?.path, retry) { mutableStateOf<Map<String, RemoteDirectory>>(emptyMap()) }
    var expanded by remember(address, directory?.path, retry) { mutableStateOf<Set<String>>(emptySet()) }
    var treeLoading by remember(address, directory?.path, retry) { mutableStateOf<Set<String>>(emptySet()) }
    var treeFailed by remember(address, directory?.path, retry) { mutableStateOf<Set<String>>(emptySet()) }
    val tree = directory?.let { flattenFileTree(it, subfolders, expanded) }.orEmpty()
    val currentFolder = directory?.path ?: folder
    val parentFolder = currentFolder?.takeUnless { it == "/" }?.substringBeforeLast('/')?.ifEmpty { "/" }
    val canGoUp = currentFolder != startingFolder && parentFolder != null
    fun toggleFolder(path: String) {
        if (path in treeLoading) return
        if (path in expanded) { expanded = expanded - path; return }
        if (path in subfolders) { expanded = expanded + path; return }
        treeLoading = treeLoading + path
        treeFailed = treeFailed - path
        scope.launch {
            try {
                val child = checkNotNull(connection).listFiles(address, path)
                subfolders = subfolders + (path to child)
                expanded = expanded + path
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { treeFailed = treeFailed + path }
            finally { treeLoading = treeLoading - path }
        }
    }
    LaunchedEffect(connection, address, requestedRevision) {
        gitError = null
        try { changes = checkNotNull(connection).gitChanges(address) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { changes = null; gitError = e.message ?: e.toString() }
        finally { loadedRevision = requestedRevision }
    }
    fun backToFolder() { file = null; offsets = listOf(1uL) }
    fun navigateBack() {
        when {
            file != null -> backToFolder()
            canGoUp -> folder = parentFolder
            else -> onClose()
        }
    }
    BackHandler(onBack = ::navigateBack)
    LaunchedEffect(connection, address, folder, file, offsets, retry, changesLoaded, showingDiff, showingMarkup) {
        if (!changesLoaded) return@LaunchedEffect
        loading = true
        failed = false
        notText = false
        missing = false
        unavailableLine = null
        page = null
        try {
            checkNotNull(connection)
            if (file == null) {
                // Failed navigation must use the requested path, never a stale listing.
                directory = null
                val result = connection.listFiles(address, folder ?: startingFolder)
                // Keep the exit anchor stable while navigating; accept the Host's canonical path.
                if (startingFolder == null || startingFolder == folder) startingFolder = result.path
                directory = result
            } else if (!showingDiff && !showingMarkup && remoteMediaKind(file!!) == null) {
                val requested = offsets.last()
                val result = try {
                    connection.readFilePage(address, file!!, requested, 200u)
                } catch (e: CoreException.Rpc) {
                    // A transcript can outlive the referenced lines. Keep the file readable.
                    if (e.kind != "invalid_params" || requested <= 1uL) throw e
                    val start = connection.readFilePage(address, file!!, 1uL, 200u)
                    if (start.lines >= requested) throw e
                    unavailableLine = requested
                    start
                }
                // herdr-core rejects non-UTF-8; valid UTF-8 containing NUL is binary too.
                notText = '\u0000' in result.text
                if (!notText) page = result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                e is CoreException.Rpc && e.kind == "not_text" -> notText = true
                e is CoreException.Rpc && e.kind == "not_found" -> missing = true
                else -> failed = true
            }
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        val header: @Composable ((() -> Unit)?, (() -> Unit)?, Boolean) -> Unit = { save, share, exportEnabled ->
            FilesHeader(
                path = file ?: currentFolder.orEmpty(), isFile = file != null, canGoUp = canGoUp,
                onBack = ::navigateBack, onClose = onClose,
                onFolder = { path -> folder = path; backToFolder() },
                onRefresh = { retry++ }, refreshing = loading || !changesLoaded,
                diffAvailable = changedFile != null && !mediaFile, fullFile = fullFile, onFullFile = { fullFile = !fullFile },
                wrap = wrap, onWrap = if (file != null && !showingDiff && page != null) ({ wrap = !wrap }) else null,
                onSend = if (file != null && !showingDiff && onSend != null) ({ onSend(quotedFileReference(file!!)) }) else null,
                onSave = save, onShare = share, exportEnabled = exportEnabled,
            )
        }
        if (file != null && !showingDiff) FileExportControls(connection, address, file!!, actions = header)
        else header(null, null, false)
        if (markupFile && !showingDiff) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceChip(selected = preview, onClick = { preview = true }, label = { Text(stringResource(R.string.files_markup_preview)) })
            SingleChoiceChip(selected = !preview, onClick = { preview = false }, label = { Text(stringResource(R.string.files_markup_source)) })
        }
        when {
            !changesLoaded || loading -> Text(stringResource(R.string.files_loading))
            file == null -> {
                val entries = directory?.entries.orEmpty()
                val parentLabel = stringResource(R.string.files_parent_folder_button)
                LazyColumn(Modifier.weight(1f).testTag("files-tree")) {
                    parentFolder?.let { parent ->
                        item(key = "parent") {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { folder = parent }
                                .semantics { contentDescription = parentLabel }
                                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_arrow_left), null, Modifier.padding(end = 8.dp).size(20.dp))
                                Text(stringResource(R.string.files_parent_folder_entry), fontFamily = FontFamily.Monospace)
                            }
                            HorizontalDivider()
                        }
                    }
                    item {
                        gitError?.let { Text(stringResource(R.string.git_error, it), color = MaterialTheme.colorScheme.error) }
                        when {
                            changes?.notRepository == true -> Text(stringResource(R.string.git_not_repository))
                            changes?.files?.isEmpty() == true && changes?.truncated == false -> Text(stringResource(R.string.git_clean))
                        }
                        if (changes?.truncated == true) Text(stringResource(R.string.git_truncated))
                        if (!changes?.files.isNullOrEmpty()) Text(stringResource(R.string.files_changed_heading), style = MaterialTheme.typography.titleSmall)
                    }
                    itemsIndexed(changes?.files.orEmpty(), key = { _, entry -> "changed:${entry.path}" }) { _, entry ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val path = gitFilePath(changes!!.cwd, entry.path)
                            if (folder == null && directory == null) folder = path.substringBeforeLast('/').ifEmpty { "/" }
                            file = path
                            offsets = listOf(1uL)
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(gitFileBadge(entry), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontFamily = FontFamily.Monospace)
                            }
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(entry.path, fontFamily = FontFamily.Monospace)
                                entry.originalPath?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        HorizontalDivider()
                    }
                    item {
                        Text(stringResource(R.string.files_folders_heading), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                        if (failed) Text(stringResource(R.string.files_failed), color = MaterialTheme.colorScheme.error)
                        else if (entries.isEmpty()) Text(stringResource(R.string.files_empty_folder))
                    }
                    itemsIndexed(if (failed) emptyList() else tree, key = { _, entry -> "tree:${entry.path}" }) { _, entry ->
                        val isExpanded = entry.path in expanded
                        val folderState = stringResource(if (isExpanded) R.string.files_folder_expanded else R.string.files_folder_collapsed)
                        Row(Modifier.fillMaxWidth().clickable {
                            if (entry.directory) toggleFolder(entry.path)
                            else { file = entry.path; offsets = listOf(1uL) }
                        }.then(if (entry.directory) Modifier.semantics { stateDescription = folderState } else Modifier)
                            .padding(start = (entry.depth * 16).dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (entry.directory) {
                                Icon(painterResource(if (isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                                    contentDescription = null, modifier = Modifier.size(16.dp))
                                Icon(painterResource(if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder),
                                    contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                            }
                            Text(if (entry.directory) "${entry.name}/" else entry.name, fontFamily = FontFamily.Monospace)
                        }
                        if (entry.path in treeLoading) Text(stringResource(R.string.files_loading))
                        if (entry.path in treeFailed) Text(stringResource(R.string.files_failed), color = MaterialTheme.colorScheme.error)
                        HorizontalDivider()
                    }
                }
            }
            notText -> Text(stringResource(R.string.files_not_text))
            missing -> Text(stringResource(R.string.files_missing, file.orEmpty()), color = MaterialTheme.colorScheme.error)
            failed -> Text(stringResource(R.string.files_failed), color = MaterialTheme.colorScheme.error)
            showingDiff -> GitFileDiff(connection, address, changes!!.cwd, changedFile, retry, onDiffSend)
            showingMarkup -> key(connection, address, file, retry) {
                RemoteMarkdownPreview(connection, address, file!!, Modifier.weight(1f))
            }
            remoteMediaKind(file!!) != null -> key(connection, address, file, retry) {
                RemoteFilePreview(connection, address, file!!, Modifier.weight(1f),
                    preview = initialLocation?.takeIf { it.path == file }?.preview)
            }
            else -> page?.let { content ->
                unavailableLine?.let { Text(stringResource(R.string.files_line_unavailable, it.toLong())) }
                if (content.lines == 0uL) Text(stringResource(R.string.files_empty_file))
                else Text(stringResource(R.string.files_range, content.first.toLong(), content.last.toLong(), content.lines.toLong()))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionIcon(R.drawable.ic_chevron_left, R.string.files_previous_button,
                        enabled = offsets.size > 1 || content.first > 1uL, onClick = {
                            offsets = if (offsets.size > 1) offsets.dropLast(1)
                                else listOf(if (content.first > 200uL) content.first - 200uL else 1uL)
                        })
                    ActionIcon(R.drawable.ic_chevron_right, R.string.files_next_button,
                        enabled = content.last < content.lines, onClick = { offsets = offsets + (content.last + 1uL) })
                }
                // Drop only the split's synthetic trailing line; blank lines in the file remain.
                val lines = remember(content) {
                    if (content.text.isEmpty()) emptyList()
                    else content.text.split('\n').let { if (content.text.endsWith('\n')) it.dropLast(1) else it }
                }
                val colors = MaterialTheme.colorScheme
                val highlighted = remember(content, file, colors) { highlightFileCode(content.text, file.orEmpty(), colors) }
                val starts = remember(lines) {
                    var offset = 0
                    lines.map { line -> offset.also { offset += line.length + 1 } }
                }
                val scroll = rememberScrollState()
                LazyColumn(Modifier.weight(1f).then(if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scroll))) {
                    itemsIndexed(lines) { index, line ->
                        val number = content.first + index.toULong()
                        val label = stringResource(R.string.files_send_line_label, number.toLong())
                        Row(Modifier.then(if (wrap) Modifier.fillMaxWidth() else Modifier).testTag("files-line:$number")
                            .then(if (onSend == null) Modifier else Modifier.clickable {
                                onSend(quotedFileReference(file!!) + ":$number\n" + line)
                            }.semantics { contentDescription = label })
                            .padding(vertical = 6.dp)) {
                            Text(number.toString(), Modifier.widthIn(min = 48.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(highlighted.subSequence(starts[index], starts[index] + line.length),
                                modifier = if (wrap) Modifier.weight(1f) else Modifier,
                                fontFamily = FontFamily.Monospace, softWrap = wrap)
                        }
                    }
                }
            }
        }
    }
}

/** POSIX shell quoting also keeps apostrophes in file paths unambiguous. */
internal fun quotedFileReference(path: String): String = "'" + path.replace("'", "'\\''") + "'"

/** Files reuse the current Host line; dismissing the panel retains the Agent view. */
@Composable
internal fun RemoteFilesAccess(model: HostsModel, content: @Composable () -> Unit) {
    val body: @Composable () -> Unit = { Column(Modifier.fillMaxSize()) { content() } }
    val screen = model.screen
    val terminal = when (screen) {
        is Screen.Chat -> screen.chat.terminal
        is Screen.Terminal -> screen.viewer.terminal
        else -> { body(); return }
    }
    val link = when (screen) {
        is Screen.Chat -> screen.chat.link
        is Screen.Terminal -> screen.viewer.link
    } ?: run { body(); return }
    var open by remember(screen) { mutableStateOf(false) }
    var noFolder by remember(screen) { mutableStateOf(false) }
    var location by remember(screen) { mutableStateOf<FileLocation?>(null) }
    fun sendReference(reference: String, diff: Boolean) {
        if (model.screen === screen) {
            model.switchView(io.github.shiroel.aries.core.AgentView.CHAT)
            (model.screen as? Screen.Chat)?.chat?.let { chat ->
                chat.draft = if (diff && chat.draft.isNotBlank()) chat.draft + "\n\n" + reference
                    else appendFileReference(chat.draft, reference)
            }
        }
        open = false
    }
    CompositionLocalProvider(LocalOpenFiles provides {
        noFolder = false; location = null; open = true
    }, LocalOpenRemoteFile provides { target ->
        noFolder = !target.path.startsWith('/') && terminal.currentCwd.isNullOrBlank()
        location = target.copy(path = if (target.path.startsWith('/')) target.path
            else terminal.currentCwd.orEmpty().trimEnd('/') + "/" + target.path)
        open = true
    }) { body() }
    if (open) androidx.compose.ui.window.Dialog(
        onDismissRequest = { open = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            if (noFolder) Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.files_no_folder))
                ActionIcon(R.drawable.ic_x, R.string.files_close_button, onClick = { open = false })
            } else RemoteFilesScreen(link.connection, terminal.address, { open = false },
                initialLocation = location,
                onSend = if (terminal.agentStatus == null) null else { reference -> sendReference(reference, false) },
                onDiffSend = if (terminal.agentStatus == null) null else { reference -> sendReference(reference, true) },
            )
        }
    }
}

internal fun appendFileReference(draft: String, reference: String): String =
    if (draft.isEmpty()) reference else "$draft\n$reference"

internal val LocalOpenFiles = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Resolve Git paths with the same normalization for folder entries and transcript links. */
private fun gitFilePath(cwd: String, path: String): String =
    java.nio.file.Paths.get(cwd, path).normalize().toString()

private fun gitFileBadge(file: GitFile): String = when {
    file.untracked || 'A' in file.status -> "A"
    'D' in file.status -> "D"
    'R' in file.status -> "R"
    'C' in file.status -> "C"
    'U' in file.status -> "U"
    else -> "M"
}

private data class FileTreeEntry(val name: String, val path: String, val directory: Boolean, val depth: Int)

private fun flattenFileTree(
    root: RemoteDirectory,
    children: Map<String, RemoteDirectory>,
    expanded: Set<String>,
): List<FileTreeEntry> = buildList {
    fun visit(folder: RemoteDirectory, depth: Int) {
        for (entry in folder.entries) {
            val path = folder.path.trimEnd('/') + "/" + entry.name
            add(FileTreeEntry(entry.name, path, entry.directory, depth))
            if (entry.directory && path in expanded) children[path]?.let { visit(it, depth + 1) }
        }
    }
    visit(root, 0)
}
