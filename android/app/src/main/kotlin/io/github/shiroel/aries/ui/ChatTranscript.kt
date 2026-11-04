package io.github.shiroel.aries.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatImage
import io.github.shiroel.aries.core.ChatResponseMetadata

/** UniFFI replaces every wrapper on each snapshot. Observe changes at lazy-row granularity
 * so streaming below the viewport does not invalidate the rows being read.
 * The list changes only when row keys/types change; payloads have independent snapshot state.
 */
@Stable
internal class ChatTranscript {
    var rows by mutableStateOf<List<ChatTranscriptRow>>(emptyList())
        private set
    private class MessageRows(val entry: ChatEntry, val document: ChatMarkdownDocument?, val rows: List<ChatTranscriptRow>)
    private var messages = emptyMap<String, MessageRows>()

    fun update(entries: List<ChatEntry>, markdown: Map<String, ChatMarkdownDocument>) {
        val nextMessages = entries.associate { entry ->
            val document = markdown[entry.id]
            val previous = messages[entry.id]
            if (previous != null && previous.entry == entry && previous.document === document) {
                // Most snapshots change one message. Avoid allocating payloads and touching
                // snapshot state for every historical Markdown block on the main thread.
                return@associate entry.id to previous
            }
            val byKey = previous?.rows?.associateBy { it.key }.orEmpty()
            val next = buildList {
                fun row(key: String, type: Any, content: ChatTranscriptContent) {
                    val row = byKey[key]?.takeIf { it.contentType == type }
                        ?: ChatTranscriptRow(entry.id, key, type, content)
                    row.content = content
                    add(row)
                }
                val body = entry.body
                val key = "transcript:${entry.id}"
                row(key, body::class, ChatTranscriptContent.Header(entry))
                if (body.markdownSource() != null) {
                    document?.blocks?.forEachIndexed { index, block ->
                        row("$key:markdown:$index", block::class,
                            ChatTranscriptContent.Markdown(document.source, block, index, body is ChatEntryBody.Thinking))
                    }
                    row("$key:footer", ChatTranscriptContent.Footer::class,
                        ChatTranscriptContent.Footer(
                            (body as? ChatEntryBody.User)?.images.orEmpty(),
                            (body as? ChatEntryBody.Agent)?.metadata,
                        ))
                }
            }
            entry.id to MessageRows(entry, document, next)
        }
        messages = nextMessages
        rows = nextMessages.values.flatMap { it.rows }
    }
}

@Stable
internal class ChatTranscriptRow(
    val entryId: String,
    val key: String,
    val contentType: Any,
    content: ChatTranscriptContent,
) {
    var content by mutableStateOf(content)
}

internal sealed interface ChatTranscriptContent {
    data class Header(val entry: ChatEntry) : ChatTranscriptContent
    data class Markdown(val source: String, val block: ChatMarkdownBlock, val index: Int, val thinking: Boolean) : ChatTranscriptContent
    data class Footer(val images: List<ChatImage>, val metadata: ChatResponseMetadata?) : ChatTranscriptContent
}
