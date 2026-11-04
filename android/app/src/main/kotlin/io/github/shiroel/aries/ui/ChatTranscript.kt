package io.github.shiroel.aries.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.structuralEqualityPolicy
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatImage
import io.github.shiroel.aries.core.ChatResponseMetadata

/** Publish the header/actions and parsed body together, and preserve equal native wrappers.
 * A raw snapshot can arrive before its Markdown. Replacing the provider at that point
 * schedules a layout with the old document followed by another when parsing completes.
 */
internal class ChatRenderedEntries {
    private var current = emptyList<ChatEntry>()
    private var byId = emptyMap<String, ChatEntry>()

    fun update(entries: List<ChatEntry>, markdown: Map<String, ChatMarkdownDocument>): List<ChatEntry> {
        val next = entries.map { entry ->
            val previous = byId[entry.id]
            val source = entry.body.markdownSource()
            when {
                previous != null && source != null && markdown[entry.id]?.source != source &&
                    previous.body::class == entry.body::class -> previous
                previous == entry -> previous!!
                else -> entry
            }
        }
        if (next.size == current.size && next.indices.all { next[it] === current[it] }) return current
        current = next
        byId = next.associateBy { it.id }
        return current
    }
}

/** Derive history from the same rendered entries/Markdown as the following renderer.
 * The provider observes row keys/types, while each row observes only its own payload.
 * Projection caches contain plain values; calculating them never writes snapshot state.
 */
@Stable
internal class ChatTranscript(
    entries: State<List<ChatEntry>>,
    markdown: State<Map<String, ChatMarkdownDocument>>,
) {
    private class ProjectedRow(val entryId: String, val key: String, val contentType: Any, val content: ChatTranscriptContent)
    private class MessageRows(val entry: ChatEntry, val document: ChatMarkdownDocument?, val rows: List<ProjectedRow>) {
        val byKey = rows.associateBy { it.key }
    }
    private class Projection(val messages: Map<String, MessageRows>, val rows: List<ProjectedRow>)
    private var previous = Projection(emptyMap(), emptyList())
    private val projection by derivedStateOf(structuralEqualityPolicy()) { project(entries.value, markdown.value) }
    private var handles = emptyMap<String, ChatTranscriptRow>()

    val rows: List<ChatTranscriptRow> by derivedStateOf(structuralEqualityPolicy()) {
        projection.rows.map { row ->
            handles[row.key]?.takeIf { it.contentType == row.contentType }
                ?: ChatTranscriptRow(row.entryId, row.key, row.contentType, derivedStateOf(structuralEqualityPolicy()) {
                    // A removed/retyped lazy item may still be observed until it is disposed.
                    projection.messages[row.entryId]?.byKey?.get(row.key)
                        ?.takeIf { it.contentType == row.contentType }?.content ?: row.content
                })
        }.also { handles = it.associateBy { row -> row.key } }
    }

    private fun project(entries: List<ChatEntry>, markdown: Map<String, ChatMarkdownDocument>): Projection {
        val nextMessages = entries.associate { entry ->
            val document = markdown[entry.id]
            val message = previous.messages[entry.id]
            if (message != null && message.entry == entry && message.document === document) {
                // Most snapshots change one message. Avoid allocating payloads for
                // every historical Markdown block on the main thread.
                return@associate entry.id to message
            }
            val byKey = message?.byKey.orEmpty()
            val next = buildList {
                fun row(key: String, type: Any, content: ChatTranscriptContent) {
                    val row = byKey[key]?.takeIf { it.contentType == type && it.content == content }
                        ?: ProjectedRow(entry.id, key, type, content)
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
        val nextRows = nextMessages.values.flatMap { it.rows }
        if (previous.rows != nextRows) previous = Projection(nextMessages, nextRows)
        return previous
    }
}

@Stable
internal class ChatTranscriptRow(
    val entryId: String,
    val key: String,
    val contentType: Any,
    content: State<ChatTranscriptContent>,
) {
    val content by content
}

internal sealed interface ChatTranscriptContent {
    data class Header(val entry: ChatEntry) : ChatTranscriptContent
    data class Markdown(val source: String, val block: ChatMarkdownBlock, val index: Int, val thinking: Boolean) : ChatTranscriptContent
    data class Footer(val images: List<ChatImage>, val metadata: ChatResponseMetadata?) : ChatTranscriptContent
}
