package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.PERMISSION_TOOL
import io.github.shiroel.aries.permissionChoiceResource
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatQuestionAnswer
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.ChatToolStatus

@Composable
internal fun QuestionView(chat: ChatModel, id: String, q: ChatEntryBody.Question, announce: Boolean = false) {
    val permission = q.name == PERMISSION_TOOL
    val mode = chat.questionMode(id)
    val state = chat.answers[id]
    val queuedAnswer = chat.queuedMessages.any { it.id == "history:$id" }
    val cancelled = q.status == ChatToolStatus.FAILED
    val unconfirmed = state == AnswerState.Unconfirmed && q.status == ChatToolStatus.RUNNING
    val submitted = state == AnswerState.Sent || state == AnswerState.Unconfirmed
    val answered = !cancelled && (q.status == ChatToolStatus.DONE || submitted)
    val entries = chat.snapshot?.entries.orEmpty()
    val expired = !answered && !cancelled && mode == ChatModel.QuestionMode.ReadOnly &&
        (chat.snapshot?.status == ChatStatus.ENDED || chat.terminal.state != "live" ||
            entries.dropWhile { it.id != id }.drop(1).any {
                val body = it.body
                body is ChatEntryBody.Question || (body is ChatEntryBody.User && !body.queued)
            })
    val open = !answered && !cancelled && mode in setOf(ChatModel.QuestionMode.Live,
        ChatModel.QuestionMode.History, ChatModel.QuestionMode.LivePending, ChatModel.QuestionMode.Reconnecting)
    val enabled = state != AnswerState.Sending && state != AnswerState.Held && mode in setOf(ChatModel.QuestionMode.Live, ChatModel.QuestionMode.History)
    var confirmingAlways by remember(id, q) { mutableStateOf(false) }
    var confirmingRetry by remember(id, q) { mutableStateOf(false) }
    val recorded = questionAnswers(q)
    val colors = MaterialTheme.colorScheme
    val accent = when {
        unconfirmed -> colors.error
        answered -> colors.primary
        cancelled -> colors.error
        expired -> colors.onSurfaceVariant
        else -> statusColor(AgentStatus.Blocked(null, null))
    }
    val label = when {
        cancelled -> R.string.chat_question_cancelled
        q.status == ChatToolStatus.DONE -> R.string.chat_question_sent
        unconfirmed -> R.string.chat_question_unconfirmed
        submitted -> R.string.chat_question_waiting
        expired -> R.string.chat_question_expired
        mode == ChatModel.QuestionMode.LivePending -> R.string.chat_question_live_pending
        mode == ChatModel.QuestionMode.Reconnecting -> R.string.chat_question_reconnecting
        mode == ChatModel.QuestionMode.Queued -> R.string.chat_question_queued
        mode == ChatModel.QuestionMode.History -> R.string.chat_question_history
        permission -> R.string.chat_permission_label
        else -> R.string.chat_question_label
    }
    if (announce) QuestionAnnouncements(chat, id)
    ConsoleChatCallSurface(accent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if ((answered && !unconfirmed) || cancelled) Icon(
                painterResource(if (answered) R.drawable.ic_check else R.drawable.ic_x),
                contentDescription = null, tint = accent, modifier = Modifier.size(18.dp),
            )
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = accent)
        }
        q.questions.forEachIndexed { part, question ->
            if (part > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
            question.header?.let {
                SearchableChatText(it, "question:$part:header", style = MaterialTheme.typography.labelLarge, color = accent)
            }
            SearchableChatText(question.text, "question:$part")
            if (open) {
                question.options.forEachIndexed { i, option ->
                    val label = if (permission) permissionChoiceResource(option.label)?.let { stringResource(it) } ?: option.label else option.label
                    ConsoleChatChoice(i + 1, label, option.description,
                        stringResource(R.string.chat_question_option_label, label),
                        enabled = enabled, multi = question.multi, selected = chat.picks[id]?.getOrNull(part)?.contains(i) == true,
                        onClick = {
                            if (permission && option.label == "Allow always") confirmingAlways = true
                            else chat.pick(id, q, part, i)
                        })
                }
                if (!permission) {
                    val inputLabel = stringResource(R.string.chat_question_text_label, question.text)
                    ConsoleField(
                        value = chat.questionText[id]?.getOrNull(part).orEmpty(),
                        onValueChange = { chat.writeAnswer(id, q, part, it) },
                        label = { Text(stringResource(R.string.chat_question_text_hint)) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = inputLabel },
                        enabled = enabled, singleLine = true, shape = RectangleShape,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            } else if (answered) {
                // The transcript is authoritative. Until its update arrives,
                // keep the successful phone submission visible in place.
                val answer = recorded[part] ?: if (submitted) ChatQuestionAnswer(
                    chat.picks[id]?.getOrNull(part).orEmpty().sorted().map(Int::toUInt),
                    chat.questionText[id]?.getOrNull(part)?.takeIf(String::isNotBlank), null,
                ) else null
                question.options.forEachIndexed { i, option ->
                    val selected = answer?.choices?.contains(i.toUInt()) == true
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) colors.primaryContainer else colors.surfaceContainer)
                            .semantics(mergeDescendants = true) { this.selected = selected }
                            .padding(horizontal = 12.dp, vertical = if (selected) 10.dp else 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val label = if (permission) permissionChoiceResource(option.label)?.let { stringResource(it) } ?: option.label else option.label
                            SearchableChatText(label, "question:$part:option:$i",
                                style = if (selected) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                        }
                        if (question.multi) Checkbox(selected, onCheckedChange = null, modifier = Modifier.size(24.dp))
                        else RadioButton(selected, onClick = null, modifier = Modifier.size(24.dp))
                    }
                }
                answer?.text?.let { text ->
                    Column(Modifier.fillMaxWidth().background(colors.primaryContainer).padding(12.dp)) {
                        Text(stringResource(R.string.chat_question_other), style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer)
                        SearchableChatText(text, "question:$part:answer", color = colors.onPrimaryContainer)
                    }
                }
                answer?.notes?.let { notes ->
                    Text(stringResource(R.string.chat_question_notes), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    SearchableChatText(notes, "question:$part:notes")
                }
            }
        }
        val lone = q.questions.size == 1 && !q.questions[0].multi
        if (open && (!lone || chat.questionText[id]?.any { it.isNotBlank() } == true)) {
            ConsoleButton(onClick = { chat.send(id, q) }, enabled = enabled && chat.complete(id, q)) {
                Text(stringResource(R.string.chat_question_send))
            }
        }
        if (unconfirmed && !queuedAnswer) {
            Text(stringResource(R.string.chat_queue_uncertain), style = MaterialTheme.typography.bodySmall, color = colors.error)
            if (mode == ChatModel.QuestionMode.History) {
                TextButton(onClick = { confirmingRetry = true }) { Text(stringResource(R.string.chat_delivery_retry_button)) }
            }
        }
        when {
            state == AnswerState.Held && !queuedAnswer -> Text(stringResource(R.string.chat_send_held), style = MaterialTheme.typography.bodySmall)
            state == AnswerState.Sending && !queuedAnswer -> Text(stringResource(R.string.chat_question_sending), style = MaterialTheme.typography.bodySmall)
            state is AnswerState.Failed -> Text(messageText(state.why),
                style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
    if (confirmingRetry && state == AnswerState.Unconfirmed && mode == ChatModel.QuestionMode.History && q.status == ChatToolStatus.RUNNING) {
        AlertDialog(
            onDismissRequest = { confirmingRetry = false },
            title = { Text(stringResource(R.string.chat_delivery_resend_title)) },
            text = { Text(stringResource(R.string.chat_queue_uncertain)) },
            confirmButton = { TextButton(onClick = {
                confirmingRetry = false
                chat.retryHistoryAnswer(id, q)
            }) { Text(stringResource(R.string.chat_delivery_retry_button)) } },
            dismissButton = { TextButton(onClick = { confirmingRetry = false }) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
    if (confirmingAlways && open && enabled) {
        val alwaysPart = q.questions.indexOfFirst { part -> part.options.any { it.label == "Allow always" } }
        val part = q.questions.getOrNull(alwaysPart)
        val alwaysOption = part?.options?.indexOfFirst { it.label == "Allow always" } ?: -1
        if (part != null && alwaysOption >= 0) AlertDialog(
            onDismissRequest = { confirmingAlways = false },
            title = { Text(stringResource(R.string.chat_permission_always_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(part.text)
                    part.options[alwaysOption].description?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingAlways = false
                    chat.pick(id, q, alwaysPart, alwaysOption)
                }) { Text(stringResource(R.string.chat_permission_allow_always)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingAlways = false }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
}

/** Observe reply state outside lazy transcript rows, including transcript-confirmed completion. */
@Composable
internal fun QuestionAnnouncements(chat: ChatModel, id: String? = null) {
    val questions = chat.snapshot?.entries.orEmpty().filter { it.body is ChatEntryBody.Question && (id == null || id == it.id) }
    val states = questions.associate { it.id to (chat.answers[it.id] to (it.body as ChatEntryBody.Question).status) }
    var previous by remember(chat, id) { mutableStateOf(states) }
    val changed = questions.lastOrNull { it.id in previous && previous[it.id] != states[it.id] }
    val state = changed?.let { chat.answers[it.id] }
    val message = when {
        changed == null -> null
        state is AnswerState.Failed -> messageText(state.why)
        state == AnswerState.Sending -> stringResource(R.string.chat_question_sending)
        state == AnswerState.Held -> stringResource(R.string.chat_send_held)
        state == AnswerState.Unconfirmed -> stringResource(R.string.chat_queue_uncertain)
        (changed.body as ChatEntryBody.Question).status == ChatToolStatus.DONE -> stringResource(R.string.chat_question_sent)
        state == AnswerState.Sent -> stringResource(R.string.chat_question_waiting)
        else -> null
    }
    AnnounceChanges(states, message)
    androidx.compose.runtime.SideEffect { previous = states }
}
