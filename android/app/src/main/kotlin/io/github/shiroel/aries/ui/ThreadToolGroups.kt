package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody

internal data class ThreadToolGroup(val entries: List<ChatEntry>, val key: String = "thread-tools:${entries.first().id}") {
    val tools: List<ChatEntry> = entries.filter { it.body is ChatEntryBody.Tool }

    val commands: Int = tools.count { entry ->
        val tool = entry.body as ChatEntryBody.Tool
        tool.details?.command != null || tool.name.lowercase(java.util.Locale.ROOT) in
            setOf("bash", "shell", "exec_command", "shell_command", "execute", "run_command")
    }

    val searches: Int = tools.count { entry ->
        (entry.body as ChatEntryBody.Tool).name.lowercase(java.util.Locale.ROOT) in
            setOf("grep", "glob", "search", "websearch", "web_search", "webfetch", "web_fetch", "find", "list_files")
    }

    val readFiles: Int = tools.mapNotNull { entry ->
        val tool = entry.body as ChatEntryBody.Tool
        if (tool.name.lowercase(java.util.Locale.ROOT) in setOf("read", "read_file", "read_text_file", "view_image")) tool.filePath() else null
    }.distinct().size

    val modifiedFiles: Map<String, ChatEntry> = buildMap {
        this@ThreadToolGroup.tools.forEach { entry ->
            val tool = entry.body as ChatEntryBody.Tool
            if (tool.diff != null || tool.name.lowercase(java.util.Locale.ROOT) in setOf("edit", "write", "multiedit", "write_file", "apply_patch")) {
                tool.filePath()?.let { put(it, entry) }
            }
        }
    }

    /** Use the shortest distinguishing suffix when several changes share a basename. */
    val modifiedFileLabels: Map<String, String> = modifiedFiles.keys.associateWith { path ->
        val parts = path.split('/').filter { it.isNotEmpty() }
        val depth = (1..parts.size).firstOrNull { count ->
            modifiedFiles.keys.none { other -> other != path &&
                other.split('/').filter { it.isNotEmpty() }.takeLast(count) == parts.takeLast(count) }
        } ?: parts.size
        parts.takeLast(depth).joinToString("/")
    }
}

/** Keep a turn's identity through both pagination and streaming, including screen recreation. */
internal class ThreadToolGrouping {
    private var previous = emptyList<ThreadToolGroup>()

    fun update(contents: List<ChatTranscriptContent>): List<ThreadToolGroup> =
        threadToolGroups(contents, previous).also { previous = it }
}

/** Groups the existing projection without changing its stable row keys or reading anchors. */
internal fun threadToolGroups(contents: List<ChatTranscriptContent>, previous: List<ThreadToolGroup> = emptyList()): List<ThreadToolGroup> = buildList {
    val byEntry = previous.flatMap { group -> group.entries.map { it.id to group } }.toMap()
    var tools = mutableListOf<ChatEntry>()
    fun flush() {
        if (tools.any { it.body is ChatEntryBody.Tool }) {
            val retained = tools.firstNotNullOfOrNull { byEntry[it.id] }
            add(if (retained != null && retained.entries == tools) retained else ThreadToolGroup(tools.toList(),
                retained?.key ?: "thread-tools:${tools.first().id}"))
        }
        tools = mutableListOf()
    }
    contents.forEach { content ->
        val entry = (content as? ChatTranscriptContent.Header)?.entry
        when (entry?.body) {
            is ChatEntryBody.Tool, is ChatEntryBody.Thinking -> tools.add(entry)
            is ChatEntryBody.User, is ChatEntryBody.Agent -> flush()
            else -> Unit // Markdown/footer rows and interactive requests do not split the turn.
        }
    }
    flush()
}

private fun ChatEntryBody.Tool.filePath(): String? {
    val path = diff?.path ?: details?.path ?: chatFileLocation(title)?.path ?: return null
    val cwd = details?.cwd
    return if (!path.startsWith('/') && !cwd.isNullOrBlank()) cwd.trimEnd('/') + "/" + path else path
}
