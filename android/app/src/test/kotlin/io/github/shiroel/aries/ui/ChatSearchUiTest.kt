package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import io.github.shiroel.aries.speech.FakeSpeechEngine
import io.github.shiroel.aries.speech.LocalSpeechEngineFactory
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ChatSearchUiTest {
    @get:Rule val compose = createComposeRule()
    private val speech = FakeSpeechEngine()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun entry(id: String, body: ChatEntryBody) = ChatEntry(id, null, body)

    private fun openChat(core: FakeCore) {
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            CompositionLocalProvider(LocalSpeechEngineFactory provides { speech }) { MaterialTheme { AriesApp(model) } }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
    }

    private fun search(query: String, count: Int = 3) {
        compose.onNodeWithContentDescription(str(R.string.chat_search_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_search_hint)).performTextInput(query)
        waitForCount(1, count)
    }

    private fun waitForCount(current: Int, count: Int) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(str(R.string.chat_search_count, current, count)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertHighlight(text: String, needle: String, active: Boolean) {
        val rendered = compose.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        val start = rendered.text.indexOf(needle)
        assertTrue("the actual match has a background span", rendered.spanStyles.any {
            it.start == start && it.end == start + needle.length && it.item.background != androidx.compose.ui.graphics.Color.Unspecified
        })
        assertTrue(rendered.getStringAnnotations("chat-search", start, start + needle.length)
            .any { it.item == if (active) "active" else "match" })
    }

    private fun assertMatchVisible(text: String, needle: String) {
        val match = compose.onNodeWithText(text, substring = true, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        match.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val glyph = layout.getBoundingBox(layout.layoutInput.text.text.indexOf(needle))
        val y = match.fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y + glyph.center.y
        val viewport = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode().boundsInRoot
        assertTrue("the selected glyph is inside the transcript viewport: $y in $viewport", y >= viewport.top && y <= viewport.bottom)
    }

    @Test
    fun searchesOfflineMessagesAndHiddenToolResultsAndFullDiffWithoutStealingTheDraft() {
        val core = FakeCore()
        openChat(core)
        val entries = listOf(
            entry("u1", ChatEntryBody.User("Find **needle** in the old answer.")),
            entry("tool", ChatEntryBody.Tool("Read", "cached output", "{}", ChatToolStatus.DONE, "a NEEDLE in the result")),
            entry("edit", ChatEntryBody.Tool("Edit", "/repo/file", "{}", ChatToolStatus.DONE, "updated",
                ChatDiff("/repo/file", listOf(ChatDiffHunk(1u, 1u, (1..40).map { "+line $it" } + "+last needle")), false))),
            entry("a1", ChatEntryBody.Agent("end of the cached Chat")),
        )
        core.pushChat(ChatSnapshot(entries, ChatStatus.RECONNECTING, "c:1"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("end of the cached Chat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("my unsent draft")
        search("needle")
        compose.onNodeWithText(str(R.string.chat_search_count, 1, 3)).assertExists()
        assertHighlight("Find needle in the old answer.", "needle", true)
        compose.onNodeWithText(str(R.string.chat_search_next)).performClick()
        compose.onNodeWithText(str(R.string.chat_search_count, 2, 3)).assertExists()
        compose.onNodeWithText("a NEEDLE in the result").assertIsDisplayed()
        assertHighlight("a NEEDLE in the result", "NEEDLE", true)
        compose.onNodeWithText(str(R.string.chat_search_next)).performClick()
        compose.onNodeWithText(str(R.string.chat_search_count, 3, 3)).assertExists()
        assertHighlight("+last needle", "needle", true)
        assertMatchVisible("+last needle", "needle")
        compose.onNodeWithText(str(R.string.chat_search_next)).performClick()
        compose.onNodeWithText(str(R.string.chat_search_count, 1, 3)).assertExists()
        compose.onNodeWithText(str(R.string.chat_search_previous)).performClick()
        compose.onNodeWithText(str(R.string.chat_search_count, 3, 3)).assertExists()
        compose.onNodeWithText(str(R.string.chat_search_close)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_search_hint)).assertDoesNotExist()
        compose.onNodeWithText("my unsent draft").assertExists()
        assertEquals(listOf("h1" to "t1"), core.chatsOpened)
    }
    @Test
    fun aPhraseAcrossLazyParagraphChunksIsOneResultAndBothPartsAreHighlighted() {
        val core = FakeCore()
        openChat(core)
        val first = "x".repeat(2042) + "needle"
        core.pushChat(ChatSnapshot(listOf(entry("long", ChatEntryBody.Agent(first + " across tail"))), ChatStatus.RECONNECTING, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText(" across tail").fetchSemanticsNodes().isNotEmpty() }
        search("needle across", 1)
        compose.onNodeWithText(str(R.string.chat_search_count, 1, 1)).assertExists()
        assertHighlight(first, "needle", true)
        assertMatchVisible(first, "needle")
        assertHighlight(" across tail", " across", true)
    }

    @Test
    fun retainsTheCurrentOccurrenceOnLiveAppendAndHandlesReplacementAndNoMatches() {
        val core = FakeCore()
        openChat(core)
        val old = entry("old", ChatEntryBody.User("needle needle"))
        core.pushChat(ChatSnapshot(listOf(old, entry("tail", ChatEntryBody.Agent("tail"))), ChatStatus.LIVE, "c:1"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("tail").fetchSemanticsNodes().isNotEmpty() }
        search("needle", 2)
        compose.onNodeWithText(str(R.string.chat_search_next)).performClick()
        waitForCount(2, 2)
        assertHighlight("needle needle", "needle", false)

        core.pushChat(ChatSnapshot(listOf(old, entry("tail", ChatEntryBody.Agent("tail")), entry("new", ChatEntryBody.Agent("new needle"))), ChatStatus.LIVE, "c:2"))
        waitForCount(2, 3)
        compose.onNodeWithText("needle needle").assertIsDisplayed()
        val text = compose.onNodeWithText("needle needle", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.getStringAnnotations("chat-search", 7, 13).any { it.item == "active" })

        // A branch replacement drops old hits and safely selects the remaining one.
        core.pushChat(ChatSnapshot(listOf(entry("new", ChatEntryBody.Agent("new needle"))), ChatStatus.LIVE, "c:3"))
        waitForCount(1, 1)
        assertHighlight("new needle", "needle", true)
        compose.onNodeWithContentDescription(str(R.string.chat_search_hint)).performTextReplacement("absent")
        waitForCount(0, 0)
        compose.onNodeWithText(str(R.string.chat_search_next)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.chat_search_previous)).assertIsNotEnabled()
        val cleared = compose.onNodeWithText("new needle", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(cleared.getStringAnnotations("chat-search", 0, cleared.length).isEmpty())
    }

    @Test
    fun aRawToolInputMatchOpensBothToolAndRawInputDisclosures() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(
            entry("raw", ChatEntryBody.Tool("Bash", "command", "raw needle", ChatToolStatus.DONE, "finished")),
            entry("tail", ChatEntryBody.Agent("tail")),
        ), ChatStatus.RECONNECTING, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("tail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("raw needle").assertDoesNotExist()
        search("needle", 1)
        compose.onNodeWithText("raw needle").assertIsDisplayed()
        assertHighlight("raw needle", "needle", true)
    }
    @Test
    fun searchAndReadAloudRemainAvailableTogetherWithoutRestartingSpeech() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(entry("reply", ChatEntryBody.Agent("A needle response."))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("A needle response.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("read-aloud:reply").performScrollTo().performClick()
        assertEquals(listOf("A needle response."), speech.spoken)
        search("needle", 1)
        assertHighlight("A needle response.", "needle", true)
        assertMatchVisible("A needle response.", "needle")
        compose.onNodeWithTag("read-aloud-stop").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.chat_search_next)).performClick()
        assertEquals(1, speech.spoken.size)
        compose.onNodeWithText(str(R.string.chat_search_close)).performClick()
        compose.onNodeWithTag("read-aloud-stop").performClick()
        compose.onNodeWithTag("read-aloud-stop").assertDoesNotExist()
        compose.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("read-aloud:reply"))
        compose.onNodeWithTag("read-aloud:reply").assertIsDisplayed()
    }
}
