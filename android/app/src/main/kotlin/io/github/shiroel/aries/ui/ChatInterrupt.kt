package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.InterruptOutcome

internal fun interruptMessage(outcome: InterruptOutcome): Message = Message(when (outcome) {
    InterruptOutcome.STOPPED -> R.string.chat_interrupt_stopped
    InterruptOutcome.EXITED -> R.string.chat_interrupt_exited
    InterruptOutcome.STILL_WORKING -> R.string.chat_interrupt_still_working
    InterruptOutcome.UNKNOWN -> R.string.chat_interrupt_unknown
    InterruptOutcome.NOT_WORKING -> R.string.chat_interrupt_not_working
})

@Composable
internal fun ChatInterruptNotice(chat: ChatModel, modifier: Modifier = Modifier) {
    chat.interruptNotice?.let { message ->
        AnnouncedText(
            stringResource(message.res, *message.args.toTypedArray()),
            modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun ChatInterruptProgress() {
    val label = stringResource(R.string.chat_interrupt_pending)
    CircularProgressIndicator(
        Modifier.size(20.dp).semantics { contentDescription = label },
        strokeWidth = 2.dp,
    )
}

/** Shares the composer's send slot; confirmation still rechecks live eligibility. */
@Composable
internal fun ChatInterruptButton(chat: ChatModel, onConfirmed: () -> Unit = {}) {
    var confirming by remember(chat) { mutableStateOf(false) }
    LaunchedEffect(chat.canInterrupt) {
        if (!chat.canInterrupt) confirming = false
    }
    val label = stringResource(R.string.chat_interrupt_confirm_button)
    IconButton(
        onClick = { confirming = true },
        enabled = chat.canInterrupt,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        if (chat.interrupting) ChatInterruptProgress()
        else Icon(painterResource(R.drawable.ic_square), contentDescription = null, modifier = Modifier.size(20.dp))
    }
    if (confirming && chat.canInterrupt) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.chat_interrupt_title)) },
            text = { Text(stringResource(R.string.chat_interrupt_body, displayName(chat.terminal))) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onConfirmed(); chat.interrupt() }) {
                    Text(stringResource(R.string.chat_interrupt_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
}
