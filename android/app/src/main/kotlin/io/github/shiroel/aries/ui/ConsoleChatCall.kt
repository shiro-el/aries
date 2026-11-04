package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
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
internal fun ConsoleChatCallSurface(accent: Color? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val blocked = accent ?: statusColor(AgentStatus.Blocked(null, null))
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
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
    multi: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selection = if (multi) Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { onClick() }
        else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Surface(
        shape = RectangleShape, border = BorderStroke(1.dp, colors.outline),
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).then(selection).semantics {
                contentDescription = accessibilityLabel
                this.selected = selected
            }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.console_chat_choice_number, number), Modifier.width(24.dp), color = colors.primary)
            Column(Modifier.weight(1f)) {
                Text(label)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (multi) Checkbox(selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(24.dp))
            else RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
        }
    }
}

/** Reads the same feed/reply state as the inbox, scoped to this Chat's Host and whole address. */
@Composable
internal fun ConsoleChatCall(entry: CallEntry, calls: CallsModel) {
    val call = entry.call
    ConsoleChatCallSurface(modifier = Modifier.presentsCalls(setOf(entry.key))) {
        Text(stringResource(R.string.console_chat_call_label), color = statusColor(AgentStatus.Blocked(null, null)),
            style = MaterialTheme.typography.labelLarge)
        AnnouncedText(call.title)
        if (call.body.isNotEmpty()) Text(call.body)
        call.answer?.let { answer ->
            AnnouncedText(stringResource(answer.state.labelResource(), answer.text), style = MaterialTheme.typography.bodySmall)
            if (answer.state != CallAnswerState.DELIVERED) answer.detail?.let {
                AnnouncedText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            entry.key in calls.replying -> AnnouncedText(stringResource(R.string.call_answer_sending))
            entry.answerable -> call.choices.forEachIndexed { index, choice ->
                ConsoleChatChoice(index + 1, choice,
                    accessibilityLabel = stringResource(R.string.call_answer_choice_label, call.title, choice),
                    onClick = { calls.reply(entry, index) })
            }
        }
        calls.replyNotes[entry.key]?.let { replyNoteText(it) }?.let { text ->
            AnnouncedText(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (entry.key in calls.replyNotes) {
            TextButton(onClick = { calls.dismissReplyNote(entry.key) }) {
                Text(stringResource(R.string.call_reply_dismiss))
            }
        }
    }
}
