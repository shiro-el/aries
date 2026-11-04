package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.GitDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared by local Git changes and read-only GitHub PR patches. */
@Composable
internal fun GitDiffView(
    patch: GitDiff,
    modifier: Modifier,
    selection: IntRange? = null,
    onSelect: ((Int) -> Unit)? = null,
    wrap: Boolean = false,
) {
    var approved by remember(patch) { mutableStateOf(false) }
    val large = remember(patch) { patch.lines.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } >= LARGE_FILE_BYTES }
    Column(modifier.fillMaxWidth()) {
        if (large && !approved) {
            LargeFileNotice { approved = true }
            return@Column
        }
        val state = rememberLazyListState()
        var query by remember(patch) { mutableStateOf("") }
        var step by remember(patch) { mutableIntStateOf(0) }
        var backwards by remember(patch) { mutableStateOf(false) }
        var match by remember(patch) { mutableStateOf<Int?>(null) }
        var noMatch by remember(patch) { mutableStateOf(false) }
        LaunchedEffect(patch, query, step) {
            if (query.isEmpty()) { match = null; noMatch = false; return@LaunchedEffect }
            val anchor = if (step == 0) -1 else match ?: -1
            val found = withContext(Dispatchers.Default) {
                val hits = patch.lines.indices
                if (backwards) hits.lastOrNull { it < anchor && patch.lines[it].text.contains(query, ignoreCase = true) }
                    ?: hits.lastOrNull { patch.lines[it].text.contains(query, ignoreCase = true) }
                else hits.firstOrNull { it > anchor && patch.lines[it].text.contains(query, ignoreCase = true) }
                    ?: hits.firstOrNull { patch.lines[it].text.contains(query, ignoreCase = true) }
            }
            match = found
            noMatch = found == null
            found?.let { state.scrollToItem(it) }
        }
        FileFindBar(query, { query = it; step = 0; backwards = false }, false, noMatch,
            { backwards = true; step++ }, { backwards = false; step++ })
        if (patch.truncated) Text(stringResource(R.string.git_truncated), Modifier.padding(12.dp))
        if (patch.binary) Text(stringResource(R.string.git_binary), Modifier.padding(12.dp))
        if (patch.lines.isEmpty() && !patch.binary) Text(stringResource(R.string.git_empty_diff), Modifier.padding(12.dp))
        val horizontal = rememberScrollState()
        LazyColumn(Modifier.weight(1f)
            .then(if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(horizontal))
            .testTag("git-diff-lines"), state = state) {
            itemsIndexed(patch.lines) { index, line ->
                val selected = selection?.contains(index) == true
                val label = stringResource(R.string.git_line_label, line.oldLine?.toString() ?: "–", line.newLine?.toString() ?: "–", line.text)
                Row(Modifier.then(if (wrap) Modifier.fillMaxWidth() else Modifier)
                    .selectable(selected, enabled = onSelect != null && (line.oldLine != null || line.newLine != null), onClick = {
                    onSelect?.invoke(index)
                }).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .semantics { contentDescription = label }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("${line.oldLine ?: ""} ${line.newLine ?: ""}", Modifier.widthIn(min = 64.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) // i18n-ok: numeric old/new line columns
                    Text(markFileMatches(AnnotatedString(line.text), query, MaterialTheme.colorScheme),
                        modifier = if (wrap) Modifier.weight(1f) else Modifier,
                        softWrap = wrap, fontFamily = FontFamily.Monospace, color = when {
                        line.newLine != null && line.oldLine == null -> MaterialTheme.colorScheme.primary
                        line.oldLine != null && line.newLine == null -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    })
                }
            }
        }
    }
}
