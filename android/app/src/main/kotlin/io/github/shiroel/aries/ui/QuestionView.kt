package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
internal fun QuestionView(chat: ChatModel, id: String, q: ChatEntryBody.Question) {
    val permission = q.name == PERMISSION_TOOL
    val mode = chat.questionMode(id)
    val state = chat.answers[id]
    val cancelled = q.status == ChatToolStatus.FAILED
    val answered = !cancelled && (q.status == ChatToolStatus.DONE || state == AnswerState.Sent)
    val entries = chat.snapshot?.entries.orEmpty()
    val expired = !answered && !cancelled && mode == ChatModel.QuestionMode.ReadOnly &&
        (chat.snapshot?.status == ChatStatus.ENDED || chat.terminal.state != "live" ||
            entries.dropWhile { it.id != id }.drop(1).any {
                val body = it.body
                body is ChatEntryBody.Question || (body is ChatEntryBody.User && !body.queued)
            })
    val open = !answered && !cancelled && (mode == ChatModel.QuestionMode.Live || mode == ChatModel.QuestionMode.History)
    val enabled = state != AnswerState.Sending
    val recorded = questionAnswers(q)
    val colors = MaterialTheme.colorScheme
    val accent = when {
        answered -> colors.primary
        cancelled -> colors.error
        expired -> colors.onSurfaceVariant
        else -> statusColor(AgentStatus.Blocked(null, null))
    }
    val label = when {
        cancelled -> R.string.chat_question_cancelled
        answered -> R.string.chat_question_sent
        expired -> R.string.chat_question_expired
        mode == ChatModel.QuestionMode.Queued -> R.string.chat_question_queued
        mode == ChatModel.QuestionMode.History -> R.string.chat_question_history
        permission -> R.string.chat_permission_label
        else -> R.string.chat_question_label
    }
    ConsoleChatCallSurface(accent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (answered || cancelled) Icon(
                painterResource(if (answered) R.drawable.ic_check else R.drawable.ic_x),
                contentDescription = null, tint = accent, modifier = Modifier.size(18.dp),
            )
            AnnouncedText(stringResource(label), style = MaterialTheme.typography.labelMedium, color = accent)
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
                        onClick = { chat.pick(id, q, part, i) })
                }
                if (!permission) {
                    val inputLabel = stringResource(R.string.chat_question_text_label, question.text)
                    OutlinedTextField(
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
                val answer = recorded[part] ?: if (state == AnswerState.Sent) ChatQuestionAnswer(
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
                                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant.copy(alpha = .65f))
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
        when {
            state == AnswerState.Sending -> AnnouncedText(stringResource(R.string.chat_question_sending), style = MaterialTheme.typography.bodySmall)
            state is AnswerState.Failed -> AnnouncedText(stringResource(state.why.res, *state.why.args.toTypedArray()),
                style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}
