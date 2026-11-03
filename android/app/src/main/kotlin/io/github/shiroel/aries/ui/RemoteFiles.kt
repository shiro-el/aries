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
) {
    var folder by remember(address) { mutableStateOf<String?>(null) }
    var directory by remember(address) { mutableStateOf<RemoteDirectory?>(null) }
    var file by remember(address) { mutableStateOf<String?>(null) }
    var offsets by remember(address) { mutableStateOf(listOf(1uL)) }
    var page by remember(address) { mutableStateOf<RemoteFilePage?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var notText by remember { mutableStateOf(false) }
    LaunchedEffect(connection, address, folder, file, offsets, retry) {
        loading = true
        failed = false
        notText = false
        page = null
        try {
            checkNotNull(connection)
            if (file == null) {
                directory = connection.listFiles(address, folder)
            } else {
                val result = connection.readFilePage(address, file!!, offsets.last(), 200u)
                // herdr-core rejects non-UTF-8; valid UTF-8 containing NUL is binary too.
                notText = '\u0000' in result.text
                if (!notText) page = result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CoreException.Rpc && e.kind == "not_text") notText = true else failed = true
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
            else -> page?.let { content ->
                if (content.lines == 0uL) Text(stringResource(R.string.files_empty_file))
                else Text(stringResource(R.string.files_range, content.first.toLong(), content.last.toLong(), content.lines.toLong()))
                if (onSend != null) Text(stringResource(R.string.files_line_hint), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = offsets.size > 1, onClick = { offsets = offsets.dropLast(1) }) {
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
                val scroll = rememberScrollState()
                LazyColumn(Modifier.weight(1f).horizontalScroll(scroll)) {
                    itemsIndexed(lines) { index, line ->
                        val number = content.first + index.toULong()
                        val label = stringResource(R.string.files_send_line_label, number.toLong())
                        Row(Modifier.testTag("files-line:$number")
                            .then(if (onSend == null) Modifier else Modifier.clickable {
                                onSend(quotedFileReference(file!!) + ":$number\n" + line)
                            }.semantics { contentDescription = label })
                            .padding(vertical = 6.dp)) {
                            Text(number.toString(), Modifier.widthIn(min = 48.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(line, fontFamily = FontFamily.Monospace, softWrap = false)
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
    CompositionLocalProvider(LocalRemoteFilesAction provides {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("files-open")) {
            Text(stringResource(R.string.files_open_button))
        }
    }) { body() }
    if (open) androidx.compose.ui.window.Dialog(
        onDismissRequest = { open = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            RemoteFilesScreen(link.connection, terminal.address, { open = false },
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
