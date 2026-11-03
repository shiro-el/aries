package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CallAnswerState

/** Shared visual treatment; transcript questions and Beacon Calls keep their own reply protocols. */
@Composable
internal fun ConsoleChatCallSurface(content: @Composable ColumnScope.() -> Unit) {
    val blocked = statusColor(AgentStatus.Blocked(null, null))
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .drawBehind { drawLine(blocked, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), strokeWidth = 2.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
    HorizontalDivider()
}

@Composable
internal fun ConsoleChatChoice(
    number: Int,
    label: String,
    description: String? = null,
    accessibilityLabel: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RectangleShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
            contentDescription = accessibilityLabel
            this.selected = selected
        },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) colors.primaryContainer else colors.surface,
            contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.console_chat_choice_number, number), Modifier.width(24.dp), color = colors.primary)
            Column(Modifier.weight(1f)) {
                Text(label)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Reads the same feed/reply state as the inbox, scoped to this Chat's Host and whole address. */
@Composable
internal fun ConsoleChatCall(entry: CallEntry, calls: CallsModel) {
    val call = entry.call
    ConsoleChatCallSurface {
        Text(stringResource(R.string.console_chat_call_label, call.id), color = statusColor(AgentStatus.Blocked(null, null)),
            style = MaterialTheme.typography.labelLarge)
        Text(call.title)
        if (call.body.isNotEmpty()) Text(call.body)
        call.answer?.let { answer ->
            Text(stringResource(answer.state.labelResource(), answer.text), style = MaterialTheme.typography.bodySmall)
            if (answer.state != CallAnswerState.DELIVERED) answer.detail?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            entry.key in calls.replying -> Text(stringResource(R.string.call_answer_sending))
            entry.answerable -> call.choices.forEachIndexed { index, choice ->
                ConsoleChatChoice(index + 1, choice,
                    accessibilityLabel = stringResource(R.string.call_answer_choice_label, call.title, choice),
                    onClick = { calls.reply(entry, index) })
            }
        }
        calls.replyNotes[entry.key]?.let { note ->
            val text = replyNoteText(note)
            Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
