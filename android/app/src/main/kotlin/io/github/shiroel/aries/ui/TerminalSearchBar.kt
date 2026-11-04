package io.github.shiroel.aries.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Android editing stays in this field; only the core's search API sees it. */
@Composable
internal fun TerminalSearchBar(viewer: TerminalViewer, open: Boolean, onClose: () -> Unit) {
    var query by rememberSaveable(viewer) { mutableStateOf("") }
    var caseSensitive by rememberSaveable(viewer) { mutableStateOf(false) }
    var regex by rememberSaveable(viewer) { mutableStateOf(false) }
    var invalid by remember(viewer) { mutableStateOf(false) }
    var busy by remember(viewer) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val searcher = viewer.searcher
    LaunchedEffect(searcher, open, query, caseSensitive, regex) {
        invalid = false
        busy = true
        try {
            if (open && query.isNotEmpty()) delay(150)
            try {
                searcher?.invoke(if (open) query else "", caseSensitive, regex)
            } catch (e: CoreException.Protocol) {
                searcher?.invoke("", caseSensitive, regex)
                throw e
            }
        } catch (_: CoreException.Protocol) {
            invalid = true
        } finally {
            busy = false
        }
    }
    if (!open) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f).testTag("terminal-search-query"),
                label = { Text(stringResource(R.string.terminal_search_hint)) },
                singleLine = true, isError = invalid, enabled = searcher != null,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
            ActionIcon(R.drawable.ic_x, R.string.terminal_search_close, { focus.clearFocus(); onClose() })
        }
        SwitchRow(stringResource(R.string.terminal_search_case), caseSensitive, { caseSensitive = it })
        SwitchRow(stringResource(R.string.terminal_search_regex), regex, { regex = it })
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canMove = !busy && !invalid && query.isNotEmpty() && searcher != null && (viewer.search?.total ?: 0u) > 0u
            for (backwards in listOf(true, false)) {
                ActionIcon(
                    if (backwards) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                    if (backwards) R.string.terminal_search_previous else R.string.terminal_search_next,
                    enabled = canMove,
                    onClick = {
                        scope.launch {
                            viewer.searchNavigator?.invoke(backwards)
                        }
                    },
                )
            }
            if (!invalid && !busy && query.isNotEmpty()) {
                val found = viewer.search
                Text(
                    if (found == null || found.total == 0u) stringResource(R.string.terminal_search_no_matches)
                    else stringResource(R.string.terminal_search_count, (found.current?.toInt() ?: -1) + 1, found.total.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (invalid) Text(stringResource(R.string.terminal_search_invalid), color = MaterialTheme.colorScheme.error)
    }
}
