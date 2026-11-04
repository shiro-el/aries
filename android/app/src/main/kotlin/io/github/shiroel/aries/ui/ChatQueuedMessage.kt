package io.github.shiroel.aries.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import io.github.shiroel.aries.R
import java.util.UUID

/** Waiting is phone-local; Agent/Uncertain may have typed input. Failed means rejected. */
enum class QueuedMessagePhase { Waiting, Agent, Uncertain, Failed }

data class QueuedChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val draft: String,
    val attachments: List<DraftAttachment>,
    val text: String,
    val before: Set<String>,
    val phase: QueuedMessagePhase = QueuedMessagePhase.Waiting,
    /** A successful sendMessage confirms Terminal input, not Agent receipt. */
    val confirmed: Boolean = false,
    /** Progress only: herdr-core is waiting for a person to stop typing. */
    val heldForTyping: Boolean = false,
    val error: String? = null,
    /** herdr-core increments this counter when a Working turn ends. */
    val dispatchTurn: ULong? = null,
)

/** Compare delivery identity despite Claude's bash wrapper and shell-quoted upload paths. */
internal fun QueuedChatMessage.matchesTranscript(text: String): Boolean {
    fun normalize(source: String): String {
        var value = source.trim()
        if (value.startsWith("<bash-input>") && value.endsWith("</bash-input>")) {
            value = "!" + value.removePrefix("<bash-input>").removeSuffix("</bash-input>").trim()
        }
        if (value.startsWith("!")) value = "!" + value.drop(1).trimStart()
        for (attachment in attachments.sortedByDescending { it.path.length }) {
            val path = attachment.path
            value = value.replace(shellQuote(path), path).replace("\"$path\"", path).replace("`$path`", path)
        }
        return value.trim()
    }
    return normalize(this.text) == normalize(text)
}

@Composable
internal fun QueuedMessageBubble(message: QueuedChatMessage, chat: ChatModel) {
    var confirmRetry by remember(message.id) { mutableStateOf(false) }
    if (confirmRetry) AlertDialog(
        onDismissRequest = { confirmRetry = false },
        title = { Text(stringResource(R.string.chat_delivery_resend_title)) },
        text = { Text(stringResource(R.string.chat_queue_uncertain)) },
        confirmButton = { TextButton(onClick = { confirmRetry = false; chat.retryMessage(message.id) }) {
            Text(stringResource(R.string.chat_delivery_retry_button))
        } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text(stringResource(R.string.cancel_button)) } },
    )
    val thread = LocalThreadChat.current != null
    val delivery: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(when {
                message.confirmed -> R.drawable.ic_check
                message.phase == QueuedMessagePhase.Uncertain || message.phase == QueuedMessagePhase.Failed -> R.drawable.ic_circle_dot
                else -> R.drawable.ic_send
            }), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(16.dp))
            Text(
                stringResource(if (message.heldForTyping) R.string.chat_send_held else when (message.phase) {
                    QueuedMessagePhase.Waiting -> if (chat.queueBlocked) R.string.chat_queue_blocked else R.string.chat_queue_waiting
                    QueuedMessagePhase.Agent -> if (message.confirmed) R.string.chat_delivery_entered else R.string.chat_sending
                    QueuedMessagePhase.Uncertain -> R.string.chat_queue_uncertain
                    QueuedMessagePhase.Failed -> R.string.chat_delivery_failed
                }),
                Modifier.weight(1f).padding(vertical = if (thread) 4.dp else 12.dp),
                style = if (thread) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            )
            if (message.confirmed) {
                IconButton(onClick = { chat.dismissEnteredMessage(message.id) }) {
                    Icon(painterResource(R.drawable.ic_x), stringResource(R.string.chat_delivery_dismiss_button))
                }
            } else if (message.phase == QueuedMessagePhase.Waiting) {
                IconButton(onClick = { chat.cancelQueuedMessage(message.id) }) {
                    Icon(painterResource(R.drawable.ic_x), stringResource(R.string.chat_queue_cancel_button))
                }
            }
        }
        message.error?.takeIf { it.isNotBlank() }?.let {
            Text(if (message.phase == QueuedMessagePhase.Uncertain) it else stringResource(R.string.chat_send_failed, it), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (message.phase == QueuedMessagePhase.Uncertain || message.phase == QueuedMessagePhase.Failed) {
            Row {
                TextButton(onClick = {
                    if (message.phase == QueuedMessagePhase.Uncertain) confirmRetry = true else chat.retryMessage(message.id)
                }, enabled = chat.canRetryMessage) { Text(stringResource(R.string.chat_delivery_retry_button)) }
                TextButton(onClick = { chat.restoreFailedMessage(message.id) }) {
                    Text(stringResource(R.string.chat_delivery_restore_button))
                }
            }
        }
    }
    val body: @Composable () -> Unit = {
        if (message.draft.isNotEmpty()) Text(message.draft, style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            message.attachments.forEach { attachment ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Text(attachment.name, Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (thread) {
        ThreadUserBubble(delivery = delivery) {
            // Match a transcript paragraph's header, block and footer spacing.
            Column(Modifier.padding(16.dp)) { body() }
        }
    } else Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
            delivery()
            body()
        }
    }
}

/** Thumbnails can be fetched again; only uploaded Host paths need recovery. */
internal data class ChatOutboxState(
    val draft: String,
    val attachments: List<DraftAttachment>,
    val messages: List<QueuedChatMessage>,
    val terminalDraft: String = "",
    val terminalComposerOpen: Boolean = false,
    val questionText: Map<String, List<String>> = emptyMap(),
    val picks: Map<String, List<Set<Int>>> = emptyMap(),
    val answers: Map<String, String> = emptyMap(),
    val waitingReturned: Boolean = false,
)

private fun attachmentsJson(attachments: List<DraftAttachment>) = org.json.JSONArray().apply {
    attachments.forEach { put(org.json.JSONObject().put("path", it.path).put("name", it.name).put("size", it.size)) }
}

private fun readAttachments(array: org.json.JSONArray) = (0 until array.length()).map {
    val value = array.getJSONObject(it)
    DraftAttachment(value.getString("path"), value.getString("name"), value.getInt("size"))
}

internal fun ChatOutboxState.encode(): String? {
    if (draft.isEmpty() && attachments.isEmpty() && messages.isEmpty() && terminalDraft.isEmpty() &&
        !terminalComposerOpen && questionText.isEmpty() && picks.isEmpty() && answers.isEmpty() && !waitingReturned) return null
    return org.json.JSONObject().put("version", 2).put("draft", draft).put("waitingReturned", waitingReturned)
        .put("terminalDraft", terminalDraft).put("terminalComposerOpen", terminalComposerOpen)
        .put("questionText", org.json.JSONObject().apply {
            questionText.forEach { (id, parts) -> put(id, org.json.JSONArray(parts)) }
        })
        .put("picks", org.json.JSONObject().apply {
            picks.forEach { (id, parts) -> put(id, org.json.JSONArray().apply {
                parts.forEach { put(org.json.JSONArray(it.toList())) }
            }) }
        })
        .put("answers", org.json.JSONObject(answers))
        .put("attachments", attachmentsJson(attachments))
        .put("messages", org.json.JSONArray().apply {
            messages.forEach { message -> put(org.json.JSONObject()
                .put("id", message.id).put("draft", message.draft).put("text", message.text)
                .put("attachments", attachmentsJson(message.attachments))
                .put("before", org.json.JSONArray(message.before.toList())).put("phase", message.phase.name)
                .put("confirmed", message.confirmed).put("error", message.error).put("dispatchTurn", message.dispatchTurn?.toString())) }
        }).toString()
}

internal fun decodeChatOutbox(content: String): ChatOutboxState {
    val value = org.json.JSONObject(content)
    require(value.getInt("version") in 1..2)
    val messages = value.getJSONArray("messages")
    return ChatOutboxState(value.getString("draft"), readAttachments(value.getJSONArray("attachments")),
        (0 until messages.length()).map {
            val message = messages.getJSONObject(it)
            val before = message.getJSONArray("before")
            QueuedChatMessage(message.getString("id"), message.getString("draft"),
                readAttachments(message.getJSONArray("attachments")), message.getString("text"),
                (0 until before.length()).map { i -> before.getString(i) }.toSet(),
                QueuedMessagePhase.valueOf(message.getString("phase")), message.optBoolean("confirmed", false),
                error = message.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                dispatchTurn = message.optString("dispatchTurn").toULongOrNull())
        }, terminalDraft = value.optString("terminalDraft"), terminalComposerOpen = value.optBoolean("terminalComposerOpen"),
        questionText = value.optJSONObject("questionText")?.let { questions ->
            questions.keys().asSequence().associateWith { id ->
                val parts = questions.getJSONArray(id)
                (0 until parts.length()).map { parts.getString(it) }
            }
        }.orEmpty(),
        picks = value.optJSONObject("picks")?.let { questions ->
            questions.keys().asSequence().associateWith { id ->
                val parts = questions.getJSONArray(id)
                (0 until parts.length()).map { part ->
                    val chosen = parts.getJSONArray(part)
                    (0 until chosen.length()).map { chosen.getInt(it) }.toSet()
                }
            }
        }.orEmpty(),
        answers = value.optJSONObject("answers")?.let { answers ->
            answers.keys().asSequence().associateWith { answers.getString(it) }
        }.orEmpty(), waitingReturned = value.optBoolean("waitingReturned"))
}

/** Recovery remains usable when the ended Agent has disabled its composer. */
@Composable
internal fun ReturnedChatDraft(chat: ChatModel) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(chat.draft, style = MaterialTheme.typography.bodyLarge)
                    chat.attachments.forEach { Text(it.name, style = MaterialTheme.typography.bodySmall) }
                }
            }
            TextButton(onClick = {
                val text = (listOf(chat.draft) + chat.attachments.map { shellQuote(it.path) }).filter(String::isNotEmpty).joinToString("\n")
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            }) { Text(stringResource(R.string.chat_queue_copy_returned_button)) }
        }
    }
}

/** Every layout reads the same phone-local queue count; no extra Host watch. */
internal val LocalChatQueueCounts = androidx.compose.runtime.staticCompositionLocalOf<(AgentKey) -> Int> { { 0 } }

@Composable
internal fun ChatQueueCount(count: Int) {
    if (count > 0) Text(
        stringResource(R.string.chat_queue_count, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
