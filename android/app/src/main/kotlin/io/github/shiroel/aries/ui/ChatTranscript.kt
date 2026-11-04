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
    private var byKey = emptyMap<String, ChatTranscriptRow>()

    fun update(entries: List<ChatEntry>, markdown: Map<String, ChatMarkdownDocument>) {
        val next = buildList {
            fun row(entryId: String, key: String, type: Any, content: ChatTranscriptContent) {
                val row = byKey[key]?.takeIf { it.contentType == type }
                    ?: ChatTranscriptRow(entryId, key, type, content)
                row.content = content
                add(row)
            }
            entries.forEach { entry ->
                val body = entry.body
                val key = "transcript:${entry.id}"
                row(entry.id, key, body::class, ChatTranscriptContent.Header(entry))
                if (body.markdownSource() != null) {
                    val document = markdown[entry.id]
                    document?.blocks?.forEachIndexed { index, block ->
                        row(entry.id, "$key:markdown:$index", block::class,
                            ChatTranscriptContent.Markdown(document.source, block, index, body is ChatEntryBody.Thinking))
                    }
                    row(entry.id, "$key:footer", ChatTranscriptContent.Footer::class,
                        ChatTranscriptContent.Footer(
                            (body as? ChatEntryBody.User)?.images.orEmpty(),
                            (body as? ChatEntryBody.Agent)?.metadata,
                        ))
                }
            }
        }
        byKey = next.associateBy { it.key }
        rows = next
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
