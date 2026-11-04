package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
internal fun ChatCatalogControls(chat: ChatModel) {
    val connection = chat.link?.connection
    val process = chat.terminal.foreground
    val cwd = process?.cwd ?: chat.terminal.cwd
    var catalog by remember(chat, process, chat.terminal.argv, cwd, connection) { mutableStateOf<AgentCatalog?>(null) }
    var loading by remember(chat) { mutableStateOf(false) }
    var failed by remember(chat) { mutableStateOf(false) }
    var revision by remember(chat) { mutableIntStateOf(0) }
    var showSkills by remember(chat) { mutableStateOf(false) }
    var query by remember(chat) { mutableStateOf("") }
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
        Text(stringResource(R.string.chat_commands_title), Modifier.padding(horizontal = 16.dp))
        CatalogStatus(loading, failed)
        if (!loading && !failed && matches.isEmpty()) Text(stringResource(R.string.chat_catalog_empty), Modifier.padding(horizontal = 16.dp))
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
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        AttachmentControls(chat)
        InputHistoryButton(chat.inputHistory, enabled = enabled) { chat.draft = it }
        ActionIcon(R.drawable.ic_library, R.string.chat_skills_button,
            { query = ""; showSkills = true }, enabled = enabled)
    }
    if (showSkills) {
        ModalBottomSheet(onDismissRequest = { showSkills = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chat_skills_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(R.drawable.ic_x, R.string.cancel_button, { showSkills = false })
                }
                Column(Modifier.fillMaxWidth()) {
                    val searchLabel = stringResource(R.string.chat_skills_search)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().semantics { contentDescription = searchLabel },
                        label = { Text(searchLabel) }, singleLine = true)
                    CatalogStatus(loading, failed)
                    val skills = catalog?.skills.orEmpty().filter { entry ->
                        listOf(entry.name, entry.description).any { it.contains(query.trim(), ignoreCase = true) }
                    }
                    PullToRefreshBox(isRefreshing = loading, onRefresh = { revision++ }, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp)) {
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
private fun CatalogStatus(loading: Boolean, failed: Boolean) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(stringResource(R.string.chat_catalog_failed), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun CatalogRow(entry: AgentCatalogEntry, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
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
