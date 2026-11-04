package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    sendsImmediately: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val sendLabel = stringResource(R.string.chat_question_send)
    val selection = if (sendsImmediately) Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = sendLabel, onClick = onClick)
        else if (multi) Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { onClick() }
        else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Surface(
        shape = RectangleShape, border = BorderStroke(1.dp, colors.outline),
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).then(selection).semantics {
                contentDescription = listOfNotNull(accessibilityLabel, description?.takeIf(String::isNotBlank)).joinToString(". ")
                if (!sendsImmediately) this.selected = selected
            }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.console_chat_choice_number, number), Modifier.width(24.dp), color = colors.primary)
            Column(Modifier.weight(1f)) {
                Text(label)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (!sendsImmediately && multi) Checkbox(selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(24.dp))
            else if (!sendsImmediately) RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
        }
    }
}

/** Reads the same feed/reply state as the inbox, scoped to this Chat's Host and whole address. */
@Composable
internal fun ConsoleChatCall(entry: CallEntry, calls: CallsModel, announce: Boolean = false, onOpenTerminal: (() -> Unit)? = null) {
    val call = entry.call
    if (announce) AnnounceChanges(Triple(call.answer, entry.key in calls.replying, calls.replyNotes[entry.key]), callReplyStatus(entry, calls))
    ConsoleChatCallSurface(modifier = Modifier.presentsCalls(setOf(entry.key))) {
        Text(stringResource(R.string.console_chat_call_label), color = readableAccent(
            statusColor(AgentStatus.Blocked(null, null)), MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.colorScheme.surfaceContainer.luminance() < .5f),
            style = MaterialTheme.typography.labelLarge)
        Text(call.title)
        if (call.body.isNotEmpty()) Text(call.body)
        call.answer?.let { answer ->
            Text(stringResource(answer.state.labelResource(), answer.text), style = MaterialTheme.typography.bodySmall)
            if (answer.state != CallAnswerState.DELIVERED) answer.detail?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (call.answer?.state == CallAnswerState.FAILED) Text(stringResource(R.string.call_reply_retry_guidance), style = MaterialTheme.typography.bodySmall)
        if (call.answer?.state == CallAnswerState.UNCONFIRMED) {
            Text(stringResource(R.string.call_reply_terminal_guidance), style = MaterialTheme.typography.bodySmall)
            onOpenTerminal?.let { TextButton(onClick = it) { Text(stringResource(R.string.terminal_open_button)) } }
        }
        when {
            entry.key in calls.replying -> Text(stringResource(R.string.call_answer_sending))
            entry.answerable -> call.choices.forEachIndexed { index, choice ->
                ConsoleChatChoice(index + 1, choice,
                    accessibilityLabel = stringResource(R.string.call_answer_choice_label, call.title, choice),
                    onClick = { calls.reply(entry, index) })
            }
        }
        calls.replyNotes[entry.key]?.let { replyNoteText(it, call.answer) }?.let { text ->
            Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (entry.key in calls.replyNotes || entry.unresolvedAnswer) {
            TextButton(onClick = { calls.dismissReplyNote(entry.key) }) {
                Text(stringResource(R.string.call_reply_dismiss))
            }
        }
    }
}

@Composable
internal fun callReplyStatus(entry: CallEntry, calls: CallsModel): String? = when {
    entry.key in calls.replying -> stringResource(R.string.call_answer_sending)
    entry.key in calls.replyNotes -> calls.replyNotes[entry.key]?.let { replyNoteText(it, entry.call.answer) }
    else -> entry.call.answer?.let { answer ->
        listOfNotNull(stringResource(answer.state.labelResource(), answer.text),
            answer.detail?.takeUnless { answer.state == CallAnswerState.DELIVERED }).joinToString(". ")
    }
}

/** Lives outside the transcript/inbox lazy list, so results survive row replacement. */
@Composable
internal fun CallReplyAnnouncements(calls: CallsModel, hostId: String? = null, address: String? = null, keys: Set<String>? = null) {
    val entries = calls.calls.filter { (hostId == null || it.host.id == hostId) && (address == null || it.call.terminal == address) && (keys == null || it.key in keys) }
    entries.forEach { entry ->
        key(entry.key) {
            AnnounceChanges(Triple(entry.call.answer, entry.key in calls.replying, calls.replyNotes[entry.key]),
                callReplyStatus(entry, calls))
        }
    }
}
