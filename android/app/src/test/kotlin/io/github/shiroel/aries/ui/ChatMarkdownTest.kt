package io.github.shiroel.aries.ui

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Markdown behavior at the Chat UI → fake core seam, including real touches and clipboard output. */
@RunWith(RobolectricTestRunner::class)
class ChatMarkdownTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private val openedUris = mutableListOf<String>()
    private fun entry(id: String, body: ChatEntryBody) = ChatEntry(id, null, body)

    private fun openChat(core: FakeCore) {
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            val uris = remember { object : UriHandler {
                override fun openUri(uri: String) { openedUris += uri }
            } }
            CompositionLocalProvider(LocalUriHandler provides uris) { MaterialTheme { AriesApp(model) } }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
    }

    @Test
    @Config(qualifiers = "h1200dp")
    fun userAndAgentMessagesRenderMarkdownInsteadOfSourceMarkers() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(
            entry("u1", ChatEntryBody.User("**User request**")),
            entry("a1", ChatEntryBody.Agent("# Result\n\nA **bold** and *gentle* reply with `inline()` code.")),
        ), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("User request").assertExists()
        compose.onNodeWithText("Result").assertExists()
        compose.onNodeWithText("A bold and gentle reply with inline() code.").assertExists()
        val text = compose.onNodeWithText("A bold and gentle reply with inline() code.").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(text.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
        assertTrue(text.spanStyles.any { it.item.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace })
        compose.onNodeWithText("**User request**").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "h1200dp")
    fun markdownListsQuotesAndTablesKeepTheirStructure() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(entry("a1", ChatEntryBody.Agent(
            "- first **item**\n  - nested\n\n3. third\n4. fourth\n\n> quoted reply\n\n" +
                "| Name | Count |\n| :--- | ---: |\n| **Aries** | 2 |",
        ))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Aries").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("• first item").assertExists()
        compose.onNodeWithText("• nested").assertExists()
        compose.onNodeWithText("3. third").assertExists()
        compose.onNodeWithText("4. fourth").assertExists()
        compose.onNodeWithText("quoted reply").assertExists()
        compose.onNodeWithText("Name").assertExists()
        compose.onNodeWithText("Count").assertExists()
        compose.onNodeWithText("2").assertExists()
    }

    @Test
    @Config(qualifiers = "h1200dp")
    fun fencedCodeIsHighlightedScrollableAndCopiesOnlyItsFullContents() {
        val core = FakeCore()
        openChat(core)
        val code = "val answer = 42\nprintln(\"hello\") // " + "wide ".repeat(40) + "\n"
        core.pushChat(ChatSnapshot(listOf(entry("a1", ChatEntryBody.Agent("```kotlin\n${code}```"))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("val answer", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("kotlin").assertExists()
        val rendered = compose.onNodeWithText("val answer", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertTrue("language keyword is highlighted", rendered.spanStyles.any { it.start == 0 && it.end == 3 && it.item.fontWeight == FontWeight.Bold })
        assertTrue("strings and numbers have distinct highlight styles", rendered.spanStyles.map { it.item.color }.distinct().size >= 3)
        val scroller = compose.onNodeWithText("val answer", substring = true).onParent()
        scroller.performTouchInput { swipeLeft() }
        assertTrue("wide code scrolls horizontally", scroller.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        val copy = compose.onNodeWithText(str(R.string.chat_code_copy_button))
        copy.performScrollTo().performClick()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals(code, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithText(str(R.string.chat_code_copied)).assertExists()
    }

    @Test
    @Config(qualifiers = "h1200dp")
    fun markdownLinksOpenThroughThePhoneAndUnsafeSchemesStayText() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(entry("a1", ChatEntryBody.Agent(
            "# One\n\n## Two\n\n### Three\n\n#### Four\n\n##### Five\n\n###### Six\n\n" +
                "[Docs](https://example.com/docs)\n\n[Blocked](javascript:alert(1))\n\n" +
                "![diagram](https://example.com/diagram.png)\n\n<script>plain HTML</script>",
        ))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Docs").fetchSemanticsNodes().isNotEmpty() }
        for (heading in listOf("One", "Two", "Three", "Four", "Five", "Six")) {
            assertTrue(compose.onNodeWithText(heading).fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        }
        compose.onNode(hasClickAction() and hasAnyAncestor(hasText("Docs")), useUnmergedTree = true).performClick()
        assertEquals(listOf("https://example.com/docs"), openedUris)
        val blocked = compose.onNodeWithText("Blocked").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(blocked.getLinkAnnotations(0, blocked.length).isEmpty())
        compose.onNodeWithText("diagram (https://example.com/diagram.png)").assertExists()
        compose.onNodeWithText("<script>plain HTML</script>", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "h1200dp")
    fun userCodeSupportsLanguageAliasesAndUnknownOrUnclosedFencesRemainReadable() {
        val core = FakeCore()
        openChat(core)
        for ((language, code) in listOf(
            "py" to "def hello(): return 42 # greeting",
            "rs" to "fn main() { let value = 42; }",
            "js" to "const value = 42; // greeting",
            "json" to "{\"enabled\": true, \"count\": 42}",
            "go" to "func main() { return }",
            "sql" to "SELECT count FROM agents WHERE count = 42",
            "sh" to "if true; then echo hello; fi",
        )) {
            // An incomplete fence is ordinary while an Agent is still streaming its reply.
            core.pushChat(ChatSnapshot(listOf(entry("u1", ChatEntryBody.User("```$language\n$code"))), ChatStatus.LIVE, null))
            compose.waitUntil(5_000) { compose.onAllNodesWithText(code).fetchSemanticsNodes().isNotEmpty() }
            val rendered = compose.onNodeWithText(code).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertTrue("$language has language-specific keywords", rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        }
        core.pushChat(ChatSnapshot(listOf(entry("u1", ChatEntryBody.User("```unknown\nplain <tag> & **literal**\n```"))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("plain <tag> & **literal**").fetchSemanticsNodes().isNotEmpty() }
        val plain = compose.onNodeWithText("plain <tag> & **literal**").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue("unknown language remains literal", plain.spanStyles.isEmpty())
        core.pushChat(ChatSnapshot(listOf(entry("u1", ChatEntryBody.User("    indented code"))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("indented code").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(str(R.string.chat_code_label)).assertExists()
    }

    @Test
    @Config(qualifiers = "h1200dp")
    fun markdownFileReferencesRetainNativeFileNavigation() {
        val core = FakeCore()
        openChat(core)
        val reads = mutableListOf<Pair<String, ULong>>()
        core.connection!!.fileReader = { _, path, offset, _ ->
            reads += path to offset
            io.github.shiroel.aries.core.RemoteFilePage("val answer = 42\n", offset, offset, 800u)
        }
        for ((source, displayed, expected) in listOf(
            Triple("See `src/Main.kt:415`.", "See src/Main.kt:415.", "/repo/src/Main.kt" to 415uL),
            Triple("`src/a file.kt:9`", "src/a file.kt:9", "/repo/src/a file.kt" to 9uL),
            Triple("[open file](<src/a file.kt:7>)", "open file", "/repo/src/a file.kt" to 7uL),
        )) {
            core.pushChat(ChatSnapshot(listOf(entry("a1", ChatEntryBody.Agent(source))), ChatStatus.LIVE, null))
            compose.waitUntil(5_000) { compose.onAllNodesWithText(displayed).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(displayed).performFirstLinkClick()
            compose.onNodeWithTag("files-line:${expected.second}").assertExists()
            assertEquals(expected, reads.last())
            compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
            compose.onNodeWithText(str(R.string.files_close_button)).performClick()
        }
    }

    @Test
    fun anUnbrokenUnicodeParagraphStaysLazyWithoutSplittingEmoji() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(entry("a1", ChatEntryBody.Agent("x" + "한글🙂".repeat(10_000) + "마지막"))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("마지막", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val tail = compose.onNodeWithText("마지막", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertTrue(tail.length < 3000)
        assertTrue(!tail.first().isLowSurrogate() && !tail.last().isHighSurrogate())
        assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().size < 40)
    }

    @Test
    fun longMarkdownKeepsOffscreenContentLazyAndCopiesCodeAcrossChunks() {
        val core = FakeCore()
        openChat(core)
        val code = (1..5000).joinToString("\n", postfix = "\n") { "val line$it = $it" }
        val paragraphs = (1..1000).joinToString("\n\n") { "Paragraph $it with **bold**, `inline` and 한글." }
        val started = System.nanoTime()
        core.pushChat(ChatSnapshot(listOf(entry("a1", ChatEntryBody.Agent("```kotlin\n${code}```\n\n$paragraphs"))), ChatStatus.LIVE, null))
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Paragraph 1000 with bold, inline and 한글.").fetchSemanticsNodes().isNotEmpty()
        }
        println("Markdown 5,000 code lines + 1,000 paragraphs: first render ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue("only the visible region is composed", compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().size < 80)
        compose.onNodeWithText("val line2500", substring = true).assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(str(R.string.chat_code_copy_button)))
        compose.onNodeWithText(str(R.string.chat_code_copy_button)).performClick()
        assertEquals(code, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithText("Paragraph 500 with bold, inline and 한글.").assertDoesNotExist()
    }

}
