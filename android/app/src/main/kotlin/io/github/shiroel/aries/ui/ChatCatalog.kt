package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CancellationException

/** Use the actual program, never the user-editable Terminal name or title. */
private fun catalogPreset(terminal: TerminalInfo): AgentPreset? {
    fun identify(argv: List<String>): AgentPreset? = argv.firstOrNull()?.substringAfterLast('/')?.let {
        when (it) {
            "claude" -> AgentPreset.CLAUDE_CODE
            "codex" -> AgentPreset.CODEX
            "opencode" -> AgentPreset.OPEN_CODE
            "pi" -> AgentPreset.PI
            else -> null
        }
    }
    return identify(terminal.foreground?.argv.orEmpty()) ?: identify(terminal.argv)
        // The Claude launch preset unsets inherited CLAUDE* variables in sh first.
        ?: if (terminal.argv.take(2) == listOf("sh", "-c") && terminal.argv.getOrNull(3) == "claude") AgentPreset.CLAUDE_CODE else null
}

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

@Composable
internal fun ChatCatalogControls(chat: ChatModel) {
    val preset = catalogPreset(chat.terminal)
    if (preset == null) {
        AttachmentControls(chat)
        return
    }
    val connection = chat.link?.connection
    val cwd = chat.terminal.foreground?.cwd ?: chat.terminal.cwd
    var catalog by remember(chat, preset, cwd, connection) { mutableStateOf<AgentCatalog?>(null) }
    var loading by remember(chat) { mutableStateOf(false) }
    var failed by remember(chat) { mutableStateOf(false) }
    var revision by remember(chat) { mutableIntStateOf(0) }
    var showSkills by remember(chat) { mutableStateOf(false) }
    var query by remember(chat) { mutableStateOf("") }
    val enabled = chat.sending == null
    LaunchedEffect(chat, preset, cwd, connection, revision) {
        loading = true
        failed = false
        try {
            catalog = (connection ?: throw CoreException.Closed()).agentCatalog(chat.terminal.address, preset, revision > 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoreException) {
            failed = true
        } finally { loading = false }
    }
    val input = chat.input
    val prefix = input.text.take(input.selection.start)
    val completing = input.selection.collapsed && prefix.startsWith('/') && prefix.drop(1).all { it.isLetterOrDigit() || it in "-_:" }
    val matches = if (completing) catalog?.commands.orEmpty().filter { it.token.startsWith(prefix, ignoreCase = true) } else emptyList()
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
    Row(Modifier.fillMaxWidth()) {
        AttachmentControls(chat)
        TextButton(onClick = { query = ""; showSkills = true }, enabled = enabled) {
            Text(stringResource(R.string.chat_skills_button))
        }
        TextButton(onClick = { revision++ }, enabled = !loading) { Text(stringResource(R.string.chat_catalog_refresh)) }
    }
    if (showSkills) {
        Dialog(onDismissRequest = { showSkills = false }) {
            Surface {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.chat_skills_title), style = MaterialTheme.typography.titleLarge)
                    val searchLabel = stringResource(R.string.chat_skills_search)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().semantics { contentDescription = searchLabel },
                        label = { Text(searchLabel) }, singleLine = true)
                    CatalogStatus(loading, failed)
                    val skills = catalog?.skills.orEmpty().filter { entry ->
                        listOf(entry.name, entry.description, entry.source.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) }
                    }
                    if (!loading && !failed && skills.isEmpty()) Text(stringResource(R.string.chat_catalog_empty))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(skills, key = { it.token }) { entry ->
                            CatalogRow(entry, enabled) {
                                chat.input = insertSkill(chat.input, entry.token)
                                showSkills = false
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = { revision++ }, enabled = !loading) { Text(stringResource(R.string.chat_catalog_refresh)) }
                        TextButton(onClick = { showSkills = false }) { Text(stringResource(R.string.cancel_button)) }
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
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(entry.token, style = MaterialTheme.typography.labelLarge)
        if (entry.argumentHint.isNotEmpty()) Text(entry.argumentHint, style = MaterialTheme.typography.bodySmall)
        if (entry.description.isNotEmpty()) Text(entry.description, style = MaterialTheme.typography.bodyMedium)
        Text(entry.source ?: stringResource(R.string.chat_catalog_builtin), style = MaterialTheme.typography.bodySmall)
    }
}
