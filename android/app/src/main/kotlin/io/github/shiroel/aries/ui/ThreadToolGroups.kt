package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody

internal data class ThreadToolGroup(val entries: List<ChatEntry>) {
    val readFiles: Int = entries.mapNotNull { entry ->
        val tool = entry.body as ChatEntryBody.Tool
        if (tool.name.lowercase(java.util.Locale.ROOT) in setOf("read", "read_file", "read_text_file", "view_image")) tool.filePath() else null
    }.distinct().size

    val modifiedFiles: Map<String, ChatEntry> = buildMap {
        this@ThreadToolGroup.entries.forEach { entry ->
            val tool = entry.body as ChatEntryBody.Tool
            if (tool.diff != null || tool.name.lowercase(java.util.Locale.ROOT) in setOf("edit", "write", "multiedit", "write_file", "apply_patch")) {
                tool.filePath()?.let { put(it, entry) }
            }
        }
    }

    val key: String get() = "thread-tools:${entries.first().id}"
}

/** Groups the existing projection without changing its stable row keys or reading anchors. */
internal fun threadToolGroups(contents: List<ChatTranscriptContent>): List<ThreadToolGroup> = buildList {
    var tools = mutableListOf<ChatEntry>()
    fun flush() {
        if (tools.isNotEmpty()) add(ThreadToolGroup(tools.toList()))
        tools = mutableListOf()
    }
    contents.forEach { content ->
        val entry = (content as? ChatTranscriptContent.Header)?.entry
        if (entry?.body is ChatEntryBody.Tool) tools.add(entry) else flush()
    }
    flush()
}

private fun ChatEntryBody.Tool.filePath(): String? {
    val path = diff?.path ?: details?.path ?: chatFileLocation(title)?.path ?: return null
    val cwd = details?.cwd
    return if (!path.startsWith('/') && !cwd.isNullOrBlank()) cwd.trimEnd('/') + "/" + path else path
}
