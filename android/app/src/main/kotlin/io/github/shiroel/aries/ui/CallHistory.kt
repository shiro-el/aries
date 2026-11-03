package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.CallReplyOutcome
import io.github.shiroel.aries.core.CallUrgency
import java.text.DateFormat
import java.util.Date

/** Every Host's Calls, newest first; each can open the Terminal that sent it. */
@Composable
fun CallsScreen(model: CallsModel, onBack: () -> Unit, onTerminal: (CallEntry) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
                Text(stringResource(R.string.calls_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        val names = model.calls.associate { it.host.id to it.host.name }
        items(model.problems.toList(), key = { "problem-${it.first}" }) { (hostId, detail) ->
            Text(
                stringResource(R.string.calls_feed_down, names[hostId] ?: hostId, detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (model.calls.isEmpty()) item { Text(stringResource(R.string.calls_empty)) }
        items(model.calls, key = { "${it.host.id}/${it.call.id}" }) { entry ->
            CallRow(
                entry,
                sending = entry.key in model.replying,
                note = model.replyNotes[entry.key],
                onChoice = { model.reply(entry, it) },
                onTerminal = { onTerminal(entry) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CallRow(
    entry: CallEntry,
    sending: Boolean,
    note: ReplyNote?,
    onChoice: (Int) -> Unit,
    onTerminal: () -> Unit,
) {
    val call = entry.call
    Column(Modifier.fillMaxWidth().testTag("call-${call.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(call.title, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.call_meta, urgencyLabel(call.urgency), entry.host.name, formatTime(call.atMs)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (call.body.isNotEmpty()) Text(call.body, style = MaterialTheme.typography.bodyMedium)
        if (call.choices.isNotEmpty()) {
            Text(stringResource(R.string.call_choices, call.choices.joinToString(" · ")), style = MaterialTheme.typography.bodySmall)
        }
        call.answer?.let { a ->
            val res = when (a.state) {
                CallAnswerState.CLAIMED -> R.string.call_answer_claimed
                CallAnswerState.DELIVERED -> R.string.call_answer_delivered
                CallAnswerState.FAILED -> R.string.call_answer_failed
                CallAnswerState.UNCONFIRMED -> R.string.call_answer_unconfirmed
            }
            Text(stringResource(res, a.text), style = MaterialTheme.typography.bodySmall)
            if (a.state != CallAnswerState.DELIVERED) {
                a.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        when {
            sending -> Text(stringResource(R.string.call_answer_sending), style = MaterialTheme.typography.bodySmall)
            entry.answerable -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                call.choices.forEachIndexed { i, choice ->
                    val label = stringResource(R.string.call_answer_choice_label, call.title, choice)
                    OutlinedButton(onClick = { onChoice(i) }, modifier = Modifier.semantics { contentDescription = label }) {
                        Text(choice)
                    }
                }
            }
        }
        note?.let { n ->
            val text = when (n) {
                is ReplyNote.NotSent -> stringResource(R.string.call_reply_not_sent, n.detail)
                is ReplyNote.Outcome -> stringResource(
                    when (n.outcome) {
                        CallReplyOutcome.ANSWERED -> R.string.call_reply_answered
                        CallReplyOutcome.NO_CALL -> R.string.call_reply_no_call
                        CallReplyOutcome.NO_CHOICE -> R.string.call_reply_no_choice
                        CallReplyOutcome.NOT_ANSWERABLE -> R.string.call_unconfirmed
                        CallReplyOutcome.NO_BEACON -> R.string.call_reply_no_beacon
                        else -> R.string.call_reply_unknown
                    },
                )
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when {
            call.terminal == null -> Text(stringResource(R.string.call_unconfirmed), style = MaterialTheme.typography.bodySmall)
            !call.topLevel -> Text(stringResource(R.string.call_from_helper), style = MaterialTheme.typography.bodySmall)
        }
        call.terminal?.let { address ->
            val label = stringResource(R.string.call_open_terminal_label, address, call.title)
            TextButton(onClick = onTerminal, modifier = Modifier.semantics { contentDescription = label }) {
                Text(stringResource(R.string.call_open_terminal))
            }
        }
    }
}

/** A Call that arrived while the app is open. */
@Composable
fun CallBanner(entry: CallEntry, onView: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(8.dp).testTag("call_banner").clickable(onClick = onView),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.call_banner_from, entry.host.name, urgencyLabel(entry.call.urgency)),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(entry.call.title, style = MaterialTheme.typography.titleMedium)
            if (entry.call.body.isNotEmpty()) {
                Text(entry.call.body, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.call_banner_dismiss)) }
                TextButton(onClick = onView) { Text(stringResource(R.string.call_banner_view)) }
            }
        }
    }
}

@Composable
private fun urgencyLabel(u: CallUrgency): String = stringResource(
    when (u) {
        CallUrgency.RECORD -> R.string.call_urgency_record
        CallUrgency.CHECK -> R.string.call_urgency_check
        CallUrgency.URGENT -> R.string.call_urgency_urgent
        CallUrgency.EMERGENCY -> R.string.call_urgency_emergency
    },
)

private fun formatTime(ms: ULong): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms.toLong()))

