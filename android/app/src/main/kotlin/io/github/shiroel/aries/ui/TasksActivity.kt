package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
        TasksPlan(chat.snapshot?.entries.orEmpty())
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
            Text(stringResource(R.string.tasks_request), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        timestamp?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

@Composable
internal fun TasksPlan(entries: List<ChatEntry>) {
    val items = remember(entries) { taskPlan(entries) }
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("tasks-plan"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tasks_plan), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val description = stringResource(when (item.status) {
                    TaskPlanStatus.COMPLETED -> R.string.tasks_plan_completed
                    TaskPlanStatus.IN_PROGRESS -> R.string.tasks_plan_in_progress
                    TaskPlanStatus.PENDING -> R.string.tasks_plan_pending
                })
                if (item.status == TaskPlanStatus.IN_PROGRESS) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Checkbox(checked = item.status == TaskPlanStatus.COMPLETED, onCheckedChange = null, modifier = Modifier.size(20.dp))
                Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(description, style = MaterialTheme.typography.labelSmall,
                    color = if (item.status == TaskPlanStatus.IN_PROGRESS) tasksAttentionColor() else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksQuestionTrace(entry: ChatEntry, chat: ChatModel, body: ChatEntryBody.Question) {
    var open by remember(entry.id) { mutableStateOf(false) }
    val live = chat.questionMode(entry.id) == ChatModel.QuestionMode.Live && chat.answers[entry.id] != AnswerState.Sent
    LaunchedEffect(entry.id, live) { if (live) open = true }
    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("tasks-question:${entry.id}")) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(body.title, maxLines = accessibleNameLines(), overflow = TextOverflow.Ellipsis)
            Text(stringResource(if (live) R.string.tasks_answer_needed else R.string.tasks_view_request), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            QuestionView(chat, entry.id, body)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksCallTrace(entry: CallEntry, calls: CallsModel) {
    var open by remember(entry.key) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("tasks-call:${entry.key}")) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(entry.call.title, color = tasksAttentionColor())
            Text(stringResource(R.string.console_chat_call_label), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ConsoleChatCall(entry, calls)
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
