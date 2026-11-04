package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Replace the current selection and keep a word boundary on both sides. */
private fun insertSkill(input: TextFieldValue, token: String): TextFieldValue {
    val start = input.selection.min
    val end = input.selection.max
    val before = input.text.take(start)
    val after = input.text.drop(end)
    val left = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val right = if (after.firstOrNull()?.isWhitespace() == true) "" else " "
    val text = before + left + token + right + after
    val cursor = before.length + left.length + token.length + 1
    return TextFieldValue(text, TextRange(cursor.coerceAtMost(text.length)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatCatalogControls(chat: ChatModel,
    composer: (@Composable (openTools: () -> Unit) -> Unit)? = null) {
    var showTools by rememberSaveable(chat) { mutableStateOf(false) }
    val connection = chat.link?.connection
    val process = chat.terminal.foreground
    val cwd = process?.cwd ?: chat.terminal.cwd
    var catalog by remember(chat, process, chat.terminal.argv, cwd, connection) { mutableStateOf<AgentCatalog?>(null) }
    var loading by remember(chat) { mutableStateOf(false) }
    var failed by remember(chat) { mutableStateOf(false) }
    var revision by remember(chat) { mutableIntStateOf(0) }
    var showSkills by rememberSaveable(chat) { mutableStateOf(false) }
    var query by rememberSaveable(chat) { mutableStateOf("") }
    val enabled = chat.canEdit
    val input = chat.input
    val prefix = input.text.take(input.selection.start)
    val completing = input.selection.collapsed && prefix.startsWith('/') && prefix.drop(1).all { it.isLetterOrDigit() || it in "-_:" }
    val browsing = completing || showSkills
    var consumedRevision by remember(chat) { mutableIntStateOf(0) }
    // Re-entering a picker consults the core's five-minute cache. While it remains
    // open, consult it again on expiry; an empty cached list gets one forced read.
    LaunchedEffect(chat, process, chat.terminal.argv, cwd, connection, revision, browsing, showSkills) {
        if (!browsing) return@LaunchedEffect
        var force = revision != consumedRevision
        consumedRevision = revision
        while (isActive) {
            loading = true
            failed = false
            try {
                val conn = connection ?: throw CoreException.Closed()
                var result = conn.chatCatalog(chat.terminal.address, force)
                val empty = if (showSkills) result.skills.isEmpty() else result.commands.isEmpty() && result.skills.isEmpty()
                if (!force && empty) result = conn.chatCatalog(chat.terminal.address, true)
                catalog = result
            } catch (e: CancellationException) { throw e
            } catch (e: CoreException) { failed = true
            } finally { loading = false }
            force = false
            delay(300_000)
        }
    }
    val matches = if (completing) (catalog?.commands.orEmpty() + catalog?.skills.orEmpty()).distinctBy { it.token }
        .filter { it.token.startsWith(prefix, ignoreCase = true) || ("/" + it.name).startsWith(prefix, ignoreCase = true) }
        else emptyList()
    if (completing) {
        Text(stringResource(R.string.chat_commands_title), Modifier.padding(horizontal = LocalLayoutTokens.current.contentPadding))
        CatalogStatus(loading, failed) { revision++ }
        if (!loading && !failed && matches.isEmpty()) Text(stringResource(R.string.chat_catalog_empty), Modifier.padding(horizontal = LocalLayoutTokens.current.contentPadding))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
            items(matches, key = { it.token }) { entry ->
                CatalogRow(entry, enabled) {
                    val end = input.text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) input.text.length else it }
                    val suffix = input.text.drop(end)
                    val text = entry.token + if (suffix.isEmpty()) " " else suffix
                    chat.input = TextFieldValue(text, TextRange(entry.token.length + 1))
                }
            }
        }
    }
    // Picker owners outlive the tools sheet: closing it must not unregister a
    // pending document/camera result or remove the newly opened history dialog.
    AttachmentControls(chat) { files, camera, attachmentsEnabled ->
        InputHistoryButton(chat.inputHistory, enabled = enabled, button = { history ->
            val skills = { query = ""; showTools = false; showSkills = true }
            if (composer == null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ActionIcon(R.drawable.ic_paperclip, R.string.attachments_file_button, files, attachmentsEnabled)
                ActionIcon(R.drawable.ic_camera, R.string.attachments_camera_button, camera, attachmentsEnabled)
                ActionIcon(R.drawable.ic_history, R.string.input_history_title, history, enabled)
                ActionIcon(R.drawable.ic_library, R.string.chat_skills_button, skills, enabled)
            } else {
                composer { showTools = true }
                if (showTools) ModalBottomSheet(onDismissRequest = { showTools = false }) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.chat_input_tools_button), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                        ChatToolRow(R.drawable.ic_paperclip, R.string.attachments_file_button, attachmentsEnabled) { showTools = false; files() }
                        ChatToolRow(R.drawable.ic_camera, R.string.attachments_camera_button, attachmentsEnabled) { showTools = false; camera() }
                        ChatToolRow(R.drawable.ic_history, R.string.input_history_title, enabled) { showTools = false; history() }
                        ChatToolRow(R.drawable.ic_library, R.string.chat_skills_button, enabled, skills)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }, onSelect = chat::appendDraft)
    }
    if (showSkills) {
        ConsoleSheet(onDismissRequest = { showSkills = false }) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = LocalLayoutTokens.current.contentPadding)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chat_skills_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(R.drawable.ic_x, R.string.cancel_button, { showSkills = false })
                }
                Column(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    val searchLabel = stringResource(R.string.chat_skills_search)
                    ConsoleField(query, { query = it }, Modifier.fillMaxWidth().semantics { contentDescription = searchLabel },
                        label = { Text(searchLabel) }, singleLine = true)
                    CatalogStatus(loading, failed) { revision++ }
                    val skills = catalog?.skills.orEmpty().filter { entry ->
                        listOf(entry.name, entry.description).any { it.contains(query.trim(), ignoreCase = true) }
                    }
                    PullToRefreshBox(isRefreshing = loading, onRefresh = { revision++ }, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            if (!loading && !failed && skills.isEmpty()) item { Text(stringResource(R.string.chat_catalog_empty)) }
                            items(skills, key = { it.token }) { entry ->
                                CatalogRow(entry, enabled) {
                                    chat.input = insertSkill(chat.input, entry.token)
                                    showSkills = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatToolRow(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(painterResource(icon), null, Modifier.size(24.dp)) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(
            headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            leadingIconColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f),
        ),
    )
}

@Composable
private fun CatalogStatus(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    if (loading) ConsoleProgress(Modifier.fillMaxWidth())
    if (failed) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.chat_catalog_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, enabled = !loading) { Text(stringResource(R.string.connect_retry_button)) }
    }
}

@Composable
private fun CatalogRow(entry: AgentCatalogEntry, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = LocalLayoutTokens.current.contentPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(entry.token, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
        if (entry.argumentHint.isNotEmpty()) Text(entry.argumentHint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (entry.description.isNotEmpty()) Text(entry.description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Compact windows expose secondary input actions on demand without covering Send. */
@Composable
internal fun ChatInputToolsDialog(chat: ChatModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_input_tools_button)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                ChatCatalogControls(chat)
                DraftAttachments(chat)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) } },
    )
}
