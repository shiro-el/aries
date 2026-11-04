package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.InterruptOutcome

internal fun interruptMessage(outcome: InterruptOutcome): Message = Message(when (outcome) {
    InterruptOutcome.STOPPED -> R.string.chat_interrupt_stopped
    InterruptOutcome.EXITED -> R.string.chat_interrupt_exited
    InterruptOutcome.STILL_WORKING -> R.string.chat_interrupt_still_working
    InterruptOutcome.UNKNOWN -> R.string.chat_interrupt_unknown
    InterruptOutcome.NOT_WORKING -> R.string.chat_interrupt_not_working
})

@Composable
internal fun ChatInterruptControls(chat: ChatModel) {
    var confirming by remember(chat) { mutableStateOf(false) }
    LaunchedEffect(chat.canInterrupt) {
        if (!chat.canInterrupt) confirming = false
    }
    if ((chat.terminalAvailable && chat.terminal.agentStatus == AgentStatus.Working) || chat.interrupting) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { confirming = true }, enabled = chat.canInterrupt) {
                Text(stringResource(if (chat.interrupting) R.string.chat_interrupt_pending else R.string.chat_interrupt_button))
            }
        }
    }
    chat.interruptNotice?.let { message ->
        Text(
            stringResource(message.res, *message.args.toTypedArray()),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (confirming && chat.canInterrupt) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.chat_interrupt_title)) },
            text = { Text(stringResource(R.string.chat_interrupt_body, displayName(chat.terminal))) },
            confirmButton = {
                TextButton(onClick = { confirming = false; chat.interrupt() }) {
                    Text(stringResource(R.string.chat_interrupt_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
}
