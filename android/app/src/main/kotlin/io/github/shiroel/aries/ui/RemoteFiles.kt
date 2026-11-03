package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.RemoteDirectory
import io.github.shiroel.aries.core.RemoteFilePage
import kotlinx.coroutines.CancellationException

/** One page at a time: no unbounded accumulation of file text in the phone. */
@Composable
fun RemoteFilesScreen(
    connection: HostConnection?,
    address: String,
    onClose: () -> Unit,
    onSend: ((String) -> Unit)? = null,
    initialLocation: FileLocation? = null,
) {
    var folder by remember(address, initialLocation) { mutableStateOf(initialLocation?.path?.substringBeforeLast('/')?.ifEmpty { "/" }) }
    var directory by remember(address) { mutableStateOf<RemoteDirectory?>(null) }
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
    LaunchedEffect(connection, address, folder, file, offsets, retry) {
        loading = true
        failed = false
        notText = false
        missing = false
        unavailableLine = null
        page = null
        try {
            checkNotNull(connection)
            if (file == null) {
                directory = connection.listFiles(address, folder)
            } else if (remoteMediaKind(file!!) == null) {
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
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.files_title), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text(stringResource(R.string.files_close_button)) }
        }
        Text(file ?: directory?.path.orEmpty(), fontFamily = FontFamily.Monospace)
        Row {
            if (file != null) {
                TextButton(onClick = { file = null; offsets = listOf(1uL) }) {
                    Text(stringResource(R.string.files_back_folder_button))
                }
            } else {
                TextButton(enabled = !loading && directory?.path != null && directory?.path != "/", onClick = {
                    folder = directory!!.path.substringBeforeLast('/').ifEmpty { "/" }
                }) { Text(stringResource(R.string.files_up_button)) }
            }
            TextButton(onClick = { retry++ }, enabled = !loading) { Text(stringResource(R.string.files_refresh_button)) }
        }
        file?.let { FileExportControls(connection, address, it) }
        if (file != null && onSend != null) {
            TextButton(onClick = { onSend(quotedFileReference(file!!)) }) {
                Text(stringResource(R.string.files_send_file_button))
            }
        }
        when {
            loading -> Text(stringResource(R.string.files_loading))
            notText -> Text(stringResource(R.string.files_not_text))
            missing -> Text(stringResource(R.string.files_missing, file.orEmpty()), color = MaterialTheme.colorScheme.error)
            failed -> Text(stringResource(R.string.files_failed), color = MaterialTheme.colorScheme.error)
            file == null -> {
                val entries = directory?.entries.orEmpty()
                if (entries.isEmpty()) Text(stringResource(R.string.files_empty_folder))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(entries, key = { _, entry -> entry.name }) { _, entry ->
                        Text(
                            if (entry.directory) "${entry.name}/" else entry.name,
                            Modifier.fillMaxWidth().clickable {
                                val path = directory!!.path.trimEnd('/') + "/" + entry.name
                                if (entry.directory) folder = path else { file = path; offsets = listOf(1uL) }
                            }.padding(vertical = 12.dp),
                            fontFamily = FontFamily.Monospace,
                        )
                        HorizontalDivider()
                    }
                }
            }
            remoteMediaKind(file!!) != null -> key(connection, address, file, retry) {
                RemoteFilePreview(connection, address, file!!, Modifier.weight(1f))
            }
            else -> page?.let { content ->
                unavailableLine?.let { Text(stringResource(R.string.files_line_unavailable, it.toLong())) }
                if (content.lines == 0uL) Text(stringResource(R.string.files_empty_file))
                else Text(stringResource(R.string.files_range, content.first.toLong(), content.last.toLong(), content.lines.toLong()))
                if (onSend != null) Text(stringResource(R.string.files_line_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { wrap = !wrap }) {
                    Checkbox(checked = wrap, onCheckedChange = null)
                    Text(stringResource(R.string.files_wrap_lines))
                }
                Row {
                    TextButton(enabled = offsets.size > 1 || content.first > 1uL, onClick = {
                        offsets = if (offsets.size > 1) offsets.dropLast(1)
                            else listOf(if (content.first > 200uL) content.first - 200uL else 1uL)
                    }) {
                        Text(stringResource(R.string.files_previous_button))
                    }
                    TextButton(enabled = content.last < content.lines, onClick = { offsets = offsets + (content.last + 1uL) }) {
                        Text(stringResource(R.string.files_next_button))
                    }
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


internal val LocalRemoteFilesAction = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

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
    CompositionLocalProvider(LocalRemoteFilesAction provides {
        TextButton(onClick = { noFolder = false; location = null; open = true }, modifier = Modifier.testTag("files-open")) {
            Text(stringResource(R.string.files_open_button))
        }
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
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.files_close_button)) }
            } else RemoteFilesScreen(link.connection, terminal.address, { open = false },
                initialLocation = location,
                onSend = if (terminal.agentStatus == null) null else { reference ->
                    if (model.screen === screen) {
                        model.switchView(io.github.shiroel.aries.core.AgentView.CHAT)
                        (model.screen as? Screen.Chat)?.chat?.let { chat ->
                            chat.draft = appendFileReference(chat.draft, reference)
                        }
                    }
                    open = false
                },
            )
        }
    }
}

internal fun appendFileReference(draft: String, reference: String): String =
    if (draft.isEmpty()) reference else "$draft\n$reference"
