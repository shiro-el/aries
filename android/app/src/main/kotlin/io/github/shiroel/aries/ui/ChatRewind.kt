package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatStatus

internal data class ChatRewindTarget(val entryId: String, val text: String)

/** Transcript order cannot reliably identify a CLI picker row (branches, paging, duplicate prompts).
 * Only open the picker; the user checks its actual screen and makes the final selection.
 */
internal val ChatModel.supportsRewind: Boolean
    get() = terminal.currentProgram?.lowercase() in setOf("claude", "codex")

internal val ChatModel.canRewind: Boolean
    get() = supportsRewind && !inputUnavailable && link?.state == LinkState.Up && link?.connection != null &&
        snapshot?.status in setOf(ChatStatus.LIVE, ChatStatus.WAITING) &&
        terminal.agentStatus != AgentStatus.Working && terminal.agentStatus !is AgentStatus.Blocked &&
        sending == null && !interrupting && queuedMessages.isEmpty()

internal fun ChatModel.rewindTarget(entryId: String?): ChatRewindTarget? {
    if (!supportsRewind || entryId == null) return null
    val user = snapshot?.entries?.firstOrNull { it.id == entryId }?.body as? ChatEntryBody.User ?: return null
    if (user.queued) return null
    return ChatRewindTarget(entryId, user.text)
}

@Composable
internal fun ChatRewindConfirmation(dialogs: ChatDialogState, chat: ChatModel?, onRewind: (String) -> Unit) {
    val target = dialogs.rewind ?: return
    val eligible = chat?.canRewind == true && chat.rewindTarget(target.entryId) == target
    AlertDialog(
        onDismissRequest = { dialogs.rewind = null },
        title = { Text(stringResource(R.string.chat_rewind_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_rewind_body))
                Text(target.text, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                if (!eligible) Text(stringResource(R.string.chat_rewind_unavailable), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = eligible, onClick = {
                // Eligibility is live, including a status or transcript change while confirming.
                if (chat?.canRewind == true && chat.rewindTarget(target.entryId) == target) {
                    dialogs.rewind = null
                    onRewind(target.entryId)
                }
            }) { Text(stringResource(R.string.chat_rewind_open_button)) }
        },
        dismissButton = {
            TextButton(onClick = { dialogs.rewind = null }) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
