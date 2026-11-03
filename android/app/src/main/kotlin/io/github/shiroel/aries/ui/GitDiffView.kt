package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import io.github.shiroel.aries.core.GitDiff

/** Shared by local Git changes and read-only GitHub PR patches. */
@Composable
internal fun GitDiffView(
    patch: GitDiff,
    modifier: Modifier,
    visible: Int,
    onMore: () -> Unit,
    selection: IntRange? = null,
    onSelect: ((Int) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (patch.truncated) Text(stringResource(R.string.git_truncated), Modifier.padding(12.dp))
        if (patch.binary) Text(stringResource(R.string.git_binary), Modifier.padding(12.dp))
        if (patch.lines.isEmpty() && !patch.binary) Text(stringResource(R.string.git_empty_diff), Modifier.padding(12.dp))
        val horizontal = rememberScrollState()
        LazyColumn(Modifier.weight(1f).horizontalScroll(horizontal).testTag("git-diff-lines")) {
            itemsIndexed(patch.lines.take(visible)) { index, line ->
                val selected = selection?.contains(index) == true
                val label = stringResource(R.string.git_line_label, line.oldLine?.toString() ?: "–", line.newLine?.toString() ?: "–", line.text)
                Row(Modifier.fillMaxWidth().selectable(selected, enabled = onSelect != null && (line.oldLine != null || line.newLine != null), onClick = {
                    onSelect?.invoke(index)
                }).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .semantics { contentDescription = label }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("${line.oldLine ?: ""} ${line.newLine ?: ""}", Modifier.widthIn(min = 64.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) // i18n-ok: numeric old/new line columns
                    Text(line.text, fontFamily = FontFamily.Monospace, color = when {
                        line.newLine != null && line.oldLine == null -> MaterialTheme.colorScheme.primary
                        line.oldLine != null && line.newLine == null -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    })
                }
            }
            if (patch.lines.size > visible) item {
                TextButton(onClick = onMore) { Text(stringResource(R.string.git_more_button)) }
            }
        }
    }
}
