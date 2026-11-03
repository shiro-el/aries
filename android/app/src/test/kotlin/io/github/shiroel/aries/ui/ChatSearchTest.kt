package io.github.shiroel.aries.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Literal search and navigation are independent of the connection and visible viewport. */
class ChatSearchTest {
    @Test
    fun findsEveryLiteralOccurrenceInTranscriptOrderIncludingUnicodeAndHiddenToolText() {
        val fields = listOf(
            ChatSearchField("old", "message", "Error [a.b] error 한글", 1),
            ChatSearchField("tool", "output", "ERROR in cached result", 5),
            ChatSearchField("edit", "diff", "+error\n-error", 6),
        )
        val results = searchChat(fields, "error")
        assertEquals(listOf("old", "old", "tool", "edit", "edit"), results.map { it.field.entryId })
        assertEquals(listOf(0, 12, 0, 1, 8), results.map { it.start })
        assertEquals(listOf(5, 17, 5, 6, 13), results.map { it.end })
        assertEquals(listOf(1, 1, 5, 6, 6), results.map { it.field.itemIndex })
        assertEquals(1, searchChat(fields, "[a.b]").size)
        assertEquals(1, searchChat(fields, "한글").size)
        assertEquals(emptyList<ChatSearchMatch>(), searchChat(fields, ""))
        assertEquals(emptyList<ChatSearchMatch>(), searchChat(fields, "missing"))
    }

    @Test
    fun nextAndPreviousWrapAndKeepTheSelectedOccurrenceWhenLiveTextIsAppended() {
        val old = ChatSearchField("u1", "message", "one one", 1)
        val results = searchChat(listOf(old), "one")
        assertEquals(results[0], navigateChatSearch(results, null, 1))
        assertEquals(results[1], navigateChatSearch(results, results[0], 1))
        assertEquals(results[0], navigateChatSearch(results, results[1], 1))
        assertEquals(results[1], navigateChatSearch(results, results[0], -1))
        val updated = searchChat(listOf(old.copy(text = "one one one", itemIndex = 4)), "one")
        assertEquals(1, selectedChatSearchIndex(updated, results[1]))
        assertEquals(0, selectedChatSearchIndex(updated, ChatSearchMatch(old.copy(entryId = "removed"), 0, 3)))
        assertEquals(-1, selectedChatSearchIndex(emptyList(), results[1]))
        assertNull(navigateChatSearch(emptyList(), null, -1))
    }
}
