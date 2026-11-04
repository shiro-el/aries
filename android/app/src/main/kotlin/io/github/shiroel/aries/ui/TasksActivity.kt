package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Keeps Chat's lazy row keys, search indices, markdown, outbox and scroll ownership intact. */
@Composable
internal fun TasksActivityEntry(entry: ChatEntry, chat: ChatModel, content: @Composable () -> Unit) {
    val body = entry.body
    if (entry.id == chat.snapshot?.entries?.firstOrNull()?.id && chat.snapshot?.entries?.none { it.body is ChatEntryBody.User } == true) {
        TasksPlan(chat)
    }
    if (body is ChatEntryBody.Question) {
        TasksQuestionTrace(entry, chat, body)
        return
    }
    val timestamp = remember(entry.timestamp) {
        runCatching { OffsetDateTime.parse(entry.timestamp).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
    }
    if (body is ChatEntryBody.Tool) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            timestamp?.let { Text(it, Modifier.padding(top = 10.dp), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Box(Modifier.weight(1f)) { content() }
        }
    } else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (body is ChatEntryBody.User && entry.id == chat.snapshot?.entries?.firstOrNull { it.body is ChatEntryBody.User }?.id) {
            Text(stringResource(R.string.tasks_request), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        timestamp?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

@Composable
internal fun TasksPlan(chat: ChatModel) {
    val entries = chat.snapshot?.entries.orEmpty()
    val items = remember(entries) { taskPlan(entries) }
    if (items.isEmpty()) return
    val working = chat.showWorking && chat.connectionError == null
    val progressLabel = when {
        chat.inputUnavailable -> R.string.tasks_plan_stopped
        chat.connectionError != null || chat.link?.state != LinkState.Up ||
            chat.snapshot?.status !in setOf(ChatStatus.LIVE, ChatStatus.WAITING) -> R.string.tasks_plan_last_known
        chat.terminal.agentStatus is AgentStatus.Error -> R.string.tasks_plan_error
        working -> R.string.tasks_plan_in_progress
        chat.terminal.agentStatus is AgentStatus.Blocked -> R.string.tasks_plan_paused
        else -> R.string.tasks_plan_stopped
    }
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("tasks-plan"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tasks_plan), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val description = stringResource(when (item.status) {
                    TaskPlanStatus.COMPLETED -> R.string.tasks_plan_completed
                    TaskPlanStatus.IN_PROGRESS -> progressLabel
                    TaskPlanStatus.PENDING -> R.string.tasks_plan_pending
                })
                val status = Modifier.semantics { stateDescription = description }
                if (item.status == TaskPlanStatus.IN_PROGRESS && working) CircularProgressIndicator(status.size(16.dp), strokeWidth = 2.dp)
                else if (item.status == TaskPlanStatus.IN_PROGRESS) Icon(painterResource(R.drawable.ic_circle_dot),
                    contentDescription = null, modifier = status.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else Checkbox(checked = item.status == TaskPlanStatus.COMPLETED, onCheckedChange = null, modifier = status.size(20.dp))
                Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (item.status == TaskPlanStatus.IN_PROGRESS && !working) {
                    Text(description, style = MaterialTheme.typography.labelSmall,
                        color = if (progressLabel == R.string.tasks_plan_error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TasksQuestionTrace(entry: ChatEntry, chat: ChatModel, body: ChatEntryBody.Question) {
    val dialogs = LocalChatDialogs.current
    val mode = chat.questionMode(entry.id)
    val state = chat.answers[entry.id]
    val label = when {
        body.status != ChatToolStatus.RUNNING -> R.string.tasks_view_request
        state == AnswerState.Unconfirmed -> R.string.chat_question_unconfirmed
        state == AnswerState.Sending -> R.string.chat_question_sending
        state == AnswerState.Held -> R.string.chat_send_held
        state == AnswerState.Sent -> R.string.chat_question_waiting
        mode == ChatModel.QuestionMode.Live -> R.string.tasks_answer_needed
        mode == ChatModel.QuestionMode.History -> R.string.tasks_answer_history
        else -> R.string.tasks_view_request
    }
    TextButton(onClick = { dialogs?.tasksRequest = TasksRequest.Question(entry.id, body) }, modifier = Modifier.fillMaxWidth().testTag("tasks-question:${entry.id}")) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(body.title, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
            if (state == AnswerState.Unconfirmed && body.status == ChatToolStatus.RUNNING) {
                Text(stringResource(R.string.chat_queue_uncertain), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
    // Reveal the selected field in-place so the search bar and next/previous actions stay usable.
    LocalChatSearch.current.active?.field?.takeIf { it.entryId == entry.id }?.let { field ->
        SearchableChatText(field.text, field.key, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

/** The screen owns the request, so lazy-row replacement cannot dismiss its sheet. */
internal sealed interface TasksRequest {
    data class Question(val id: String, val body: ChatEntryBody.Question) : TasksRequest
    data class Call(val entry: CallEntry) : TasksRequest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksRequestDialogs(dialogs: ChatDialogState, chat: ChatModel, calls: CallsModel?) {
    val request = dialogs.tasksRequest ?: return
    ModalBottomSheet(onDismissRequest = {
        if (request is TasksRequest.Question) chat.dismissTaskQuestion(request.id)
        dialogs.tasksRequest = null
    },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (request) {
                is TasksRequest.Question -> CompositionLocalProvider(LocalChatSearchEntry provides request.id) {
                    QuestionView(chat, request.id,
                        chat.snapshot?.entries?.find { it.id == request.id }?.body as? ChatEntryBody.Question ?: request.body, announce = true)
                }
                is TasksRequest.Call -> if (calls != null) ConsoleChatCall(
                    calls.calls.find { it.key == request.entry.key } ?: request.entry.copy(live = false), calls, announce = true)
            }
        }
    }
}

@Composable
internal fun TasksCallTrace(entry: CallEntry) {
    val dialogs = LocalChatDialogs.current
    TextButton(onClick = { dialogs?.tasksRequest = TasksRequest.Call(entry) }, modifier = Modifier.fillMaxWidth().testTag("tasks-call:${entry.key}")
        .presentsCalls(setOf(entry.key))) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(entry.call.title, color = tasksAttentionColor())
            Text(stringResource(R.string.console_chat_call_label), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun tasksToolVerb(name: String): String = when (name.substringAfterLast('.')) {
    "Read", "read_file" -> stringResource(R.string.tasks_tool_read)
    "Grep", "Glob", "search", "search_query" -> stringResource(R.string.tasks_tool_search)
    "Edit", "MultiEdit", "apply_patch" -> stringResource(R.string.tasks_tool_edit)
    "Write", "write_file" -> stringResource(R.string.tasks_tool_write)
    "Bash", "exec", "exec_command", "shell", "shell_command" -> stringResource(R.string.tasks_tool_run)
    else -> name
}
