package io.github.shiroel.aries.ui

import androidx.compose.runtime.mutableStateOf
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatToolDetails
import io.github.shiroel.aries.core.ChatToolStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadToolGroupsTest {
    // D70: interleaved Thinking must stay in the same fold until a User or Agent body arrives.
    @Test fun thinkingStaysWithToolsUntilTheNextConversationBody() {
        fun tool(id: String) = ChatEntry(id, null, ChatEntryBody.Tool("Bash", "check", "{}", ChatToolStatus.DONE, null))
        val entries = listOf(tool("1"), ChatEntry("thinking", null, ChatEntryBody.Thinking("Check the result")), tool("2"),
            ChatEntry("agent", null, ChatEntryBody.Agent("Done")), tool("3"),
            ChatEntry("user", null, ChatEntryBody.User("Continue")), tool("4"))
        val transcript = ChatTranscript(mutableStateOf(entries), mutableStateOf(emptyMap()))
        assertEquals(listOf(listOf("1", "thinking", "2"), listOf("3"), listOf("4")),
            threadToolGroups(transcript.contents).map { group -> group.entries.map { it.id } })
    }

    // Regression: collapsing tools must never mix calls across a user message or Agent response.
    @Test fun consecutiveToolsStayInsideTheirConversationSegment() {
        fun tool(id: String) = ChatEntry(id, null, ChatEntryBody.Tool("Read", "a.kt", "{}", ChatToolStatus.DONE, null))
        val entries = listOf(tool("1"), tool("2"),
            ChatEntry("user", null, ChatEntryBody.User("Continue")), tool("3"),
            ChatEntry("agent", null, ChatEntryBody.Agent("I found the cause.")), tool("4"), tool("5"))
        val transcript = ChatTranscript(mutableStateOf(entries), mutableStateOf(emptyMap()))
        assertEquals(listOf(listOf("1", "2"), listOf("3"), listOf("4", "5")),
            threadToolGroups(transcript.contents).map { group -> group.entries.map { it.id } })
    }
    // Regression: repeated reads/edits of one file count once, while every tool remains a step.
    @Test fun summaryCountsFilesRatherThanCallsAndRetainsTheDiffTarget() {
        fun tool(id: String, name: String, path: String?) = ChatEntry(id, null,
            ChatEntryBody.Tool(name, path ?: "check", "{}", ChatToolStatus.DONE, null,
                details = ChatToolDetails(cwd = "/repo", path = path)))
        val entries = listOf(tool("1", "Read", "a.kt"), tool("2", "read_file", "/repo/a.kt"),
            tool("3", "Read", "b.kt"), tool("4", "Read", "c.kt"),
            tool("5", "Edit", "a.kt"), tool("6", "Write", "d.kt"), tool("7", "Bash", null))
        val group = threadToolGroups(ChatTranscript(mutableStateOf(entries), mutableStateOf(emptyMap())).contents).single()
        assertEquals(3, group.readFiles)
        assertEquals(listOf("/repo/a.kt", "/repo/d.kt"), group.modifiedFiles.keys.toList())
        assertEquals(listOf("5", "6"), group.modifiedFiles.values.map { it.id })
        assertEquals(7, group.entries.size)
    }
    // D84: failed/running edits (even with a diff) must not claim a successful modification.
    @Test fun modifiedFilesOnlyIncludeSuccessfulEditsAcrossStreamingUpdates() {
        fun edit(id: String, status: ChatToolStatus) = ChatEntry(id, null,
            ChatEntryBody.Tool("Edit", "$id.kt", "{}", status, null,
                diff = io.github.shiroel.aries.core.ChatDiff("$id.kt", emptyList(), false),
                details = ChatToolDetails(cwd = "/repo", path = "$id.kt")))
        val grouping = ThreadToolGrouping()
        fun update(entries: List<ChatEntry>) = grouping.update(
            ChatTranscript(mutableStateOf(entries), mutableStateOf(emptyMap())).contents).single()
        val failed = edit("failed", ChatToolStatus.FAILED)
        val running = edit("running", ChatToolStatus.RUNNING)
        val done = edit("done", ChatToolStatus.DONE)
        assertEquals(listOf("/repo/done.kt"), update(listOf(failed, running, done)).modifiedFiles.keys.toList())
        assertEquals(listOf("/repo/running.kt", "/repo/done.kt"),
            update(listOf(failed, edit("running", ChatToolStatus.DONE), done)).modifiedFiles.keys.toList())
    }

}
