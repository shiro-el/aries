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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import io.github.shiroel.aries.R
import java.util.UUID

/** Only Waiting belongs to the phone. Every other phase may already have typed input. */
enum class QueuedMessagePhase { Waiting, Agent, Uncertain }

data class QueuedChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val draft: String,
    val attachments: List<DraftAttachment>,
    val text: String,
    val before: Set<String>,
    val phase: QueuedMessagePhase = QueuedMessagePhase.Waiting,
    val confirmed: Boolean = false,
    /** Progress only: herdr-core is waiting for a person to stop typing. */
    val heldForTyping: Boolean = false,
)

@Composable
internal fun QueuedMessageBubble(message: QueuedChatMessage, chat: ChatModel) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).alpha(0.65f),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (message.heldForTyping) R.string.chat_send_held else when (message.phase) {
                        QueuedMessagePhase.Waiting -> R.string.chat_queue_waiting
                        QueuedMessagePhase.Agent -> R.string.chat_queue_agent
                        QueuedMessagePhase.Uncertain -> R.string.chat_queue_uncertain
                    }),
                    Modifier.weight(1f).padding(vertical = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (message.phase == QueuedMessagePhase.Waiting) {
                    IconButton(onClick = { chat.cancelQueuedMessage(message.id) }) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.chat_queue_cancel_button))
                    }
                }
            }
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
    }
}

/** Thumbnails can be fetched again; only uploaded Host paths need recovery. */
internal data class ChatOutboxState(
    val draft: String,
    val attachments: List<DraftAttachment>,
    val messages: List<QueuedChatMessage>,
)

private fun attachmentsJson(attachments: List<DraftAttachment>) = org.json.JSONArray().apply {
    attachments.forEach { put(org.json.JSONObject().put("path", it.path).put("name", it.name).put("size", it.size)) }
}

private fun readAttachments(array: org.json.JSONArray) = (0 until array.length()).map {
    val value = array.getJSONObject(it)
    DraftAttachment(value.getString("path"), value.getString("name"), value.getInt("size"))
}

internal fun ChatOutboxState.encode(): String? {
    if (draft.isEmpty() && attachments.isEmpty() && messages.isEmpty()) return null
    return org.json.JSONObject().put("version", 1).put("draft", draft)
        .put("attachments", attachmentsJson(attachments))
        .put("messages", org.json.JSONArray().apply {
            messages.forEach { message -> put(org.json.JSONObject()
                .put("id", message.id).put("draft", message.draft).put("text", message.text)
                .put("attachments", attachmentsJson(message.attachments))
                .put("before", org.json.JSONArray(message.before.toList())).put("phase", message.phase.name)
                .put("confirmed", message.confirmed)) }
        }).toString()
}

internal fun decodeChatOutbox(content: String): ChatOutboxState {
    val value = org.json.JSONObject(content)
    require(value.getInt("version") == 1)
    val messages = value.getJSONArray("messages")
    return ChatOutboxState(value.getString("draft"), readAttachments(value.getJSONArray("attachments")),
        (0 until messages.length()).map {
            val message = messages.getJSONObject(it)
            val before = message.getJSONArray("before")
            QueuedChatMessage(message.getString("id"), message.getString("draft"),
                readAttachments(message.getJSONArray("attachments")), message.getString("text"),
                (0 until before.length()).map { i -> before.getString(i) }.toSet(),
                QueuedMessagePhase.valueOf(message.getString("phase")), message.optBoolean("confirmed", false))
        })
}
