package io.github.shiroel.aries.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.ChatResponseCompletion
import io.github.shiroel.aries.core.ChatResponseMetadata
import io.github.shiroel.aries.core.ChatToolDetails

/** Raw input has its own disclosure, independent of the tool header. */
@Composable
internal fun ChatRawInput(input: String) {
    var showRaw by remember { mutableStateOf(false) }
    val active = LocalChatSearch.current.active
    val searchExpanded = active?.field?.entryId == LocalChatSearchEntry.current && active?.field?.key == "tool-input"
    val visible = showRaw || searchExpanded
    val disclosureState = stringResource(if (visible) R.string.chat_details_expanded else R.string.chat_details_collapsed)
    TextButton(
        onClick = { showRaw = !showRaw },
        modifier = Modifier.semantics { stateDescription = disclosureState },
    ) { Text(stringResource(R.string.chat_tool_raw_input)) }
    if (visible) {
        ChatTextActions(input) { modifier ->
            SearchableChatText(input, "tool-input", modifier, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ChatToolDetailsView(details: ChatToolDetails) {
    details.command?.let { Detail(R.string.chat_tool_command, it) }
    details.cwd?.let { Detail(R.string.chat_tool_cwd, it) }
    details.path?.let { Detail(R.string.chat_tool_path, it, filePath = true) }
    details.pattern?.let { Detail(R.string.chat_tool_pattern, it) }
    details.resultCount?.let { Detail(R.string.chat_tool_result_count, it.toString()) }
    details.exitCode?.let { Detail(R.string.chat_tool_exit_code, it.toString()) }
    details.durationMs?.let { Detail(R.string.chat_tool_duration, it.toString()) }
}

@Composable
private fun Detail(@StringRes label: Int, value: String, filePath: Boolean = false) {
    val text = stringResource(label, value)
    val open = LocalOpenRemoteFile.current
    val color = MaterialTheme.colorScheme.primary
    val annotated = remember(text, filePath, open, color) {
        if (filePath) annotateChatFileLinks(chatToolPathText(text, value), open, color) else AnnotatedString(text)
    }
    ChatTextActions(text) { modifier ->
        Text(annotated, modifier, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** A compact, scrollable line keeps every recorded value accessible on a phone. */
@Composable
internal fun ChatResponseMetadataView(metadata: ChatResponseMetadata) {
    val parts = buildList {
        metadata.model?.let { add(stringResource(R.string.chat_response_model, it)) }
        metadata.inputTokens?.let { add(stringResource(R.string.chat_response_input_tokens, it.toString())) }
        metadata.outputTokens?.let { add(stringResource(R.string.chat_response_output_tokens, it.toString())) }
        metadata.cacheReadTokens?.let { add(stringResource(R.string.chat_response_cache_read_tokens, it.toString())) }
        metadata.cacheWriteTokens?.let { add(stringResource(R.string.chat_response_cache_write_tokens, it.toString())) }
        metadata.durationMs?.let { add(stringResource(R.string.chat_response_duration, it.toString())) }
        metadata.completion?.let {
            add(stringResource(when (it) {
                ChatResponseCompletion.COMPLETE -> R.string.chat_response_complete
                ChatResponseCompletion.INTERRUPTED -> R.string.chat_response_interrupted
                ChatResponseCompletion.ERROR -> R.string.chat_response_error
            }))
        }
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            Modifier.horizontalScroll(rememberScrollState()),
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
