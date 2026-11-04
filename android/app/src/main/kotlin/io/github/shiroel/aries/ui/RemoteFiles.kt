package io.github.shiroel.aries.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.GitChanges
import io.github.shiroel.aries.core.GitFile
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.RemoteDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A visited folder owns its listing, expansion and reading position until Files closes. */
internal class FolderView {
    var directory by mutableStateOf<RemoteDirectory?>(null)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var subfolders by mutableStateOf<Map<String, RemoteDirectory>>(emptyMap())
    var expanded by mutableStateOf<Set<String>>(emptySet())
    var treeLoading by mutableStateOf<Set<String>>(emptySet())
    var treeFailed by mutableStateOf<Set<String>>(emptySet())
    var connection: HostConnection? = null
    var revision = -1
    val scroll = LazyListState()
}

/** Navigation and visited folders belong to the Agent, beyond its composition. */
class RemoteFilesState internal constructor(val initialLocation: FileLocation? = null) {
    internal var folder by mutableStateOf(initialLocation?.path?.substringBeforeLast('/')?.ifEmpty { "/" })
    internal val folders = mutableMapOf<String?, FolderView>()
    internal val reading = mutableMapOf<String, FileReadingState>()
    internal var history by mutableStateOf<List<String?>>(emptyList())
    private var selectedFile by mutableStateOf(initialLocation?.path)
    internal var fullFile by mutableStateOf(false)
    internal var preview by mutableStateOf(false)
    internal var file: String?
        get() = selectedFile
        set(value) {
            if (value == selectedFile) return
            selectedFile = value
            fullFile = false
            preview = initialLocation?.path != value
        }
}

/** Opening a new target resets navigation; hiding the app retains the current target. */
internal class RemoteFilesPanel {
    var open by mutableStateOf(false)
    var noFolder by mutableStateOf(false)
    var navigation by mutableStateOf(RemoteFilesState())
    fun show(location: FileLocation? = null, missingFolder: Boolean = false) {
        navigation = RemoteFilesState(location)
        noFolder = missingFolder
        open = true
    }
}

/** Read-only files and changes on the current Terminal’s Host. */
@Composable
fun RemoteFilesScreen(
    connection: HostConnection?,
    address: String,
    onClose: () -> Unit,
    onSend: ((String) -> Unit)? = null,
    initialLocation: FileLocation? = null,
    onDiffSend: ((String) -> Unit)? = onSend,
    navigation: RemoteFilesState = remember(address, initialLocation) { RemoteFilesState(initialLocation) },
) {
    var folder by navigation::folder
    val folders = navigation.folders
    val view = folders.getOrPut(folder) { FolderView() }
    var directory by view::directory
    var loading by view::loading
    var failed by view::failed
    var history by navigation::history
    var file by navigation::file
    val viewerSettings = rememberFileViewerSettings()
    val wrap = viewerSettings.wrap
    var retry by remember { mutableIntStateOf(0) }
    var changes by remember(connection, address) { mutableStateOf<GitChanges?>(null) }
    var loadedRevision by remember(connection, address) { mutableIntStateOf(-1) }
    val requestedRevision = retry
    val changesLoaded = loadedRevision == requestedRevision
    var gitError by remember(connection, address) { mutableStateOf<Message?>(null) }
    var fullFile by navigation::fullFile
    val changedFile = changes?.let { git -> git.files.find { gitFilePath(git.cwd, it.path) == file?.let { path -> java.nio.file.Paths.get(path).normalize().toString() } } }
    var preview by navigation::preview
    val markupFile = file?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT) in setOf("md", "mmd", "mermaid")
    val mediaFile = remoteMediaKind(file.orEmpty()) != null
    val showingDiff = changedFile != null && !fullFile && !mediaFile
    val showingMarkup = markupFile && preview && !showingDiff
    val scope = rememberCoroutineScope()
    var subfolders by view::subfolders
    var expanded by view::expanded
    var treeLoading by view::treeLoading
    var treeFailed by view::treeFailed
    val tree = directory?.let { flattenFileTree(it, subfolders, expanded) }.orEmpty()
    val currentFolder = directory?.path ?: folder
    val parentFolder = currentFolder?.takeUnless { it == "/" }?.substringBeforeLast('/')?.ifEmpty { "/" }
    val canGoUp = history.isNotEmpty()
    fun navigateFolder(path: String) {
        if (path != currentFolder) { history = history + listOf(folder); folder = path }
        file = null
    }
    val treeJobs = remember(view) { mutableListOf<Job>() }
    DisposableEffect(connection, view, retry) {
        onDispose { treeJobs.forEach { it.cancel() }; treeJobs.clear() }
    }
    fun toggleFolder(path: String) {
        val conn = connection ?: return
        if (path in treeLoading) return
        if (path in expanded) { expanded = expanded - path; return }
        if (path in subfolders) { expanded = expanded + path; return }
        treeLoading = treeLoading + path
        treeFailed = treeFailed - path
        treeJobs += scope.launch {
            try {
                val child = conn.listFiles(address, path)
                subfolders = subfolders + (path to child)
                expanded = expanded + path
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { treeFailed = treeFailed + path }
            finally { treeLoading = treeLoading - path }
        }
    }
    LaunchedEffect(connection, address, requestedRevision) {
        if (connection == null) return@LaunchedEffect
        gitError = null
        try { changes = connection.gitChanges(address) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { changes = null; gitError = errorMessage(e) }
        finally { loadedRevision = requestedRevision }
    }
    fun backToFolder() { file = null }
    fun navigateBack() {
        when {
            file != null -> backToFolder()
            canGoUp -> { folder = history.last(); history = history.dropLast(1) }
            else -> onClose()
        }
    }
    BackHandler(onBack = ::navigateBack)
    LaunchedEffect(connection, address, folder, retry) {
        if (connection == null || view.connection === connection && view.revision == retry) return@LaunchedEffect
        loading = true
        failed = false
        try {
            val result = connection.listFiles(address, folder)
            directory = result
            // Refresh children on a new connection/revision; Back alone keeps the tree intact.
            subfolders = emptyMap()
            expanded = emptySet()
            treeFailed = emptySet()
            view.connection = connection
            view.revision = retry
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        val header: @Composable ((() -> Unit)?, (() -> Unit)?, Boolean) -> Unit = { save, share, exportEnabled ->
            FilesHeader(
                path = file ?: currentFolder.orEmpty(), isFile = file != null, canGoUp = canGoUp,
                onBack = ::navigateBack, onClose = onClose,
                onFolder = ::navigateFolder,
                onRefresh = { retry++ }, refreshing = connection == null || file == null && loading,
                diffAvailable = changedFile != null && !mediaFile, fullFile = fullFile, onFullFile = { fullFile = !fullFile },
                wrap = wrap, onWrap = if (file != null && !showingMarkup && !mediaFile) ({ viewerSettings.chooseWrap(!wrap) }) else null,
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
            connection == null || file == null && loading && directory == null -> Text(stringResource(R.string.files_loading))
            file == null -> {
                val entries = directory?.entries.orEmpty()
                val parentLabel = stringResource(R.string.files_parent_folder_button)
                LazyColumn(Modifier.weight(1f).testTag("files-tree"), state = view.scroll) {
                    item {
                        if (!changesLoaded) Text(stringResource(R.string.files_loading))
                        gitError?.let { Text(stringResource(R.string.git_error, messageText(it)), color = MaterialTheme.colorScheme.error) }
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
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            FileTypeIcon(entry.path, Modifier.padding(end = 8.dp))
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
                    }
                    parentFolder?.let { parent ->
                        item(key = "parent") {
                            FileTreeRow(
                                name = stringResource(R.string.files_parent_folder_entry),
                                folderIcon = R.drawable.ic_folder_up,
                                modifier = Modifier.clickable(role = Role.Button) { navigateFolder(parent) }
                                    .semantics { contentDescription = parentLabel },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (!failed && entries.isEmpty()) item {
                        Text(stringResource(R.string.files_empty_folder))
                    }
                    itemsIndexed(if (failed) emptyList() else tree, key = { _, entry -> "tree:${entry.path}" }) { _, entry ->
                        val isExpanded = entry.path in expanded
                        val folderState = stringResource(if (isExpanded) R.string.files_folder_expanded else R.string.files_folder_collapsed)
                        FileTreeRow(
                            name = if (entry.directory) "${entry.name}/" else entry.name,
                            depth = entry.depth,
                            filePath = entry.name,
                            directory = entry.directory,
                            expanded = if (entry.directory) isExpanded else null,
                            modifier = Modifier.clickable {
                                if (entry.directory) toggleFolder(entry.path)
                                else { file = entry.path }
                            }.then(if (entry.directory) Modifier.semantics { stateDescription = folderState } else Modifier),
                        )
                        if (entry.path in treeLoading) Text(stringResource(R.string.files_loading))
                        if (entry.path in treeFailed) Text(stringResource(R.string.files_failed), color = MaterialTheme.colorScheme.error)
                        HorizontalDivider()
                    }
                }
            }
            showingDiff -> GitFileDiff(connection, address, changes!!.cwd, changedFile, retry, wrap, onDiffSend)
            showingMarkup -> key(connection, address, file, retry) {
                RemoteMarkdownPreview(connection, address, file!!, Modifier.weight(1f))
            }
            remoteMediaKind(file!!) != null -> key(connection, address, file, retry) {
                RemoteFilePreview(connection, address, file!!, Modifier.weight(1f),
                    preview = initialLocation?.takeIf { it.path == file }?.preview)
            }
            else -> key(connection, address, file, retry) {
                RemoteTextFile(connection, address, file!!,
                    initialLocation?.takeIf { it.path == file }?.line ?: 1uL, wrap, onSend, onRefresh = {
                        navigation.reading.remove(file)
                        retry++
                    }, reading = navigation.reading.getOrPut(file!!) {
                        FileReadingState(initialLocation?.takeIf { it.path == file }?.line ?: 1uL)
                    })
            }
        }
    }
}

/** Parent navigation and folders share their icon slots, text alignment and touch target. */
@Composable
private fun FileTreeRow(
    name: String,
    modifier: Modifier = Modifier,
    depth: Int = 0,
    folderIcon: Int? = null,
    expanded: Boolean? = null,
    filePath: String? = null,
    directory: Boolean = false,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .padding(start = (depth * 16).dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (folderIcon != null || filePath != null) {
            if (expanded == null) Spacer(Modifier.size(16.dp))
            else Icon(painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                contentDescription = null, modifier = Modifier.size(16.dp))
            if (filePath != null) FileTypeIcon(filePath, Modifier.padding(end = 8.dp), directory, expanded == true)
            else Icon(painterResource(checkNotNull(folderIcon)), contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(20.dp))
        }
        Text(name, fontFamily = FontFamily.Monospace)
    }
}

/** POSIX shell quoting also keeps apostrophes in file paths unambiguous. */
internal fun quotedFileReference(path: String): String = "'" + path.replace("'", "'\\''") + "'"

/** Files reuse the current Host line; dismissing the panel retains the Agent view. */
@Composable
internal fun RemoteFilesAccess(model: HostsModel, content: @Composable () -> Unit) {
    val screen = model.screen
    val terminal = when (screen) {
        is Screen.Chat -> screen.chat.terminal
        is Screen.Terminal -> screen.viewer.terminal
        else -> null
    }
    val link = when (screen) {
        is Screen.Chat -> screen.chat.link
        is Screen.Terminal -> screen.viewer.link
        else -> null
    }
    val panel = when (screen) {
        is Screen.Chat -> screen.chat.files
        is Screen.Terminal -> screen.viewer.files
        else -> null
    }
    // Keep navigation content at one composition location with or without an open Agent.
    val openFiles: (() -> Unit)? = if (panel != null && link != null) ({ panel.show() }) else null
    val openRemoteFile: ((FileLocation) -> Unit)? = if (panel != null && terminal != null && link != null) ({ target ->
        panel.show(target.copy(path = if (target.path.startsWith('/')) target.path
            else terminal.currentCwd.orEmpty().trimEnd('/') + "/" + target.path),
            missingFolder = !target.path.startsWith('/') && terminal.currentCwd.isNullOrBlank())
    }) else null
    CompositionLocalProvider(LocalOpenFiles provides openFiles, LocalOpenRemoteFile provides openRemoteFile) {
        Column(Modifier.fillMaxSize()) { content() }
    }
    if (panel == null || terminal == null || link == null) return
    var open by panel::open
    val noFolder = panel.noFolder
    val location = panel.navigation.initialLocation
    fun sendReference(reference: String, diff: Boolean) {
        if (model.screen === screen) {
            model.switchView(io.github.shiroel.aries.core.AgentView.CHAT)
            (model.screen as? Screen.Chat)?.chat?.let { chat ->
                chat.appendDraft(reference, separator = if (diff && chat.draft.isNotBlank()) "\n\n" else "\n")
            }
        }
        open = false
    }
    if (open) androidx.compose.ui.window.Dialog(
        onDismissRequest = { open = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            if (noFolder) Column(Modifier.padding(LocalLayoutTokens.current.contentPadding)) {
                Text(stringResource(R.string.files_no_folder))
                ActionIcon(R.drawable.ic_x, R.string.files_close_button, onClick = { open = false })
            } else Column(Modifier.fillMaxSize()) {
                val connection by link.connections.collectAsState()
                ConnectionNotice(link)
                RemoteFilesScreen(connection, terminal.address, { open = false },
                    initialLocation = location,
                    navigation = panel.navigation,
                    onSend = if (terminal.agentStatus == null) null else { reference -> sendReference(reference, false) },
                    onDiffSend = if (terminal.agentStatus == null) null else { reference -> sendReference(reference, true) },
                )
            }
        }
    }
}

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
