package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException

/** One Host/Terminal's local input, shared across both composer surfaces. */
class InputHistoryModel(
    private val core: AriesCoreInterface,
    private val hostId: String,
    private val terminal: String,
) {
    var open by mutableStateOf(false)
    var entries by mutableStateOf<List<String>>(emptyList())
        private set
    var failed by mutableStateOf(false)
        private set

    fun show() {
        entries = emptyList()
        try {
            entries = core.inputHistory(hostId, terminal)
            failed = false
        } catch (_: CoreException) {
            failed = true
        }
        open = true
    }

    fun remember(text: String) {
        try {
            core.rememberInput(hostId, terminal, text)
            failed = false
        } catch (_: CoreException) {
            // Sending already succeeded: a history failure must never offer a resend.
            failed = true
        }
    }

    fun clear() {
        try {
            core.clearInputHistory(hostId, terminal)
            entries = emptyList()
            failed = false
        } catch (_: CoreException) {
            failed = true
        }
    }
}

@Composable
internal fun InputHistoryButton(history: InputHistoryModel, enabled: Boolean = true,
    button: (@Composable (show: () -> Unit) -> Unit)? = null, onSelect: (String) -> Unit) {
    if (button != null) button(history::show)
    else ActionIcon(R.drawable.ic_history, R.string.input_history_title, history::show, enabled)
    if (!history.open) return
    AlertDialog(
        onDismissRequest = { history.open = false },
        title = { Text(stringResource(R.string.input_history_title)) },
        text = {
            Column {
                if (history.failed) {
                    Text(stringResource(R.string.input_history_failed), color = MaterialTheme.colorScheme.error)
                } else if (history.entries.isEmpty()) {
                    Text(stringResource(R.string.input_history_empty))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(history.entries) { _, entry ->
                        Text(
                            entry,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                onSelect(entry)
                                history.open = false
                            }.padding(vertical = 12.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { history.open = false }) { Text(stringResource(R.string.cancel_button)) }
        },
        dismissButton = {
            TextButton(onClick = history::clear, enabled = history.entries.isNotEmpty()) {
                Text(stringResource(R.string.input_history_clear_button))
            }
        },
    )
}

/** History inserts a previous input without discarding work already in the composer. */
internal fun appendInputHistory(draft: String, entry: String): String =
    if (draft.isEmpty()) entry else "$draft\n\n$entry"
