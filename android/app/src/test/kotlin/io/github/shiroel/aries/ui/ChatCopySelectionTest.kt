package io.github.shiroel.aries.ui

import android.content.ClipboardManager
import android.widget.Magnifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.RemoteFilePage
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Seam 3: long-press actions in a live Chat against the fake core. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp", shadows = [ShadowChatMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalFoundationApi::class)
class ChatCopySelectionTest {
    private val generation = androidx.compose.runtime.mutableIntStateOf(0)
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private val core = FakeCore()
    // Only replace the Android context-menu boundary; selection and copying
    // still run through Compose's real SelectionContainer and clipboard.
    private var textMenu: TextContextMenuDataProvider? = null
    private val menuClosed = CompletableDeferred<Unit>()
    private val toolbar = object : TextContextMenuProvider {
        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            textMenu = dataProvider
            try { menuClosed.await() } finally { textMenu = null }
        }
    }

    private fun open() {
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                androidx.compose.runtime.key(generation.intValue) { MaterialTheme { AriesApp(model) } }
            }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
    }

    private fun show(body: ChatEntryBody, rendered: String? = null) {
        core.pushChat(ChatSnapshot(listOf(ChatEntry("entry", null, body)), ChatStatus.LIVE, null))
        compose.waitForIdle()
        val text = rendered ?: when (body) {
            is ChatEntryBody.User -> body.text
            is ChatEntryBody.Agent -> body.text
            else -> null
        }
        if (text != null) compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clipboard(): String? = context.getSystemService(ClipboardManager::class.java)
        .primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun longPressCopiesTheWholePromptOrResponseWithoutLabels() {
        open()
        val prompt = "Fix this:\n  val 이름 = \"hello\"\n"
        val response = "**Done**\n```kotlin\nprintln(\"안녕\")\n```"
        for ((body, text, rendered) in listOf(
            Triple(ChatEntryBody.User(prompt), prompt, "Fix this:"),
            Triple(ChatEntryBody.Agent(response), response, "Done"),
            Triple(ChatEntryBody.Thinking(response), response, "Done"),
            Triple(ChatEntryBody.AgentError(response), response, response),
        )) {
            show(body, rendered)
            compose.onNodeWithText(rendered, substring = true).performTouchInput { longClick() }
            compose.onNodeWithText(str(R.string.chat_select_text)).assertExists()
            compose.onNodeWithText(str(R.string.chat_copy_all)).performClick()
            compose.waitForIdle()
            assertEquals(text, clipboard())
            compose.onNodeWithText(str(R.string.chat_copy_all)).assertDoesNotExist()
        }
    }

    @Test
    fun toolsCopyTheFullResultWhileCollapsedAndEachBodyWhileExpanded() {
        open()
        val input = "{\n  \"command\": \"printf hello\"\n}"
        val result = "hello\n  오류: 자세한 내용\n"
        show(ChatEntryBody.Tool("Bash", "printf hello", input, ChatToolStatus.FAILED, result))
        val tool = compose.onNode(hasToolAction(str(R.string.chat_tool_expand_label, "Bash")))
        tool.performTouchInput { longClick() }
        compose.onNodeWithText(str(R.string.chat_copy_all)).performClick()
        assertEquals(result, clipboard())
        // Long press and dismissing the menu did not expand the tool.
        compose.onNodeWithText(result).assertDoesNotExist()
        tool.performClick()
        compose.onNodeWithText(input).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.chat_tool_raw_input)).performClick()
        for (text in listOf(input, result)) {
            compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { longClick() }
            compose.onNodeWithText(str(R.string.chat_copy_all)).performClick()
            assertEquals(text, clipboard())
        }
        tool.performClick()
        compose.onNodeWithText(result).assertDoesNotExist()
    }

    @Test
    fun aCollapsedToolCopiesInputBeforeItsResultArrivesAndDoesNotTruncateLongResults() {
        open()
        val input = "{\"command\":\"cat log.txt\"}"
        val result = "start\n" + "progress\n".repeat(500) + "끝\n"
        for (output in listOf(null, result)) {
            show(ChatEntryBody.Tool("Bash", "cat log.txt", input,
                if (output == null) ChatToolStatus.RUNNING else ChatToolStatus.DONE, output))
            compose.onNode(hasToolAction(str(R.string.chat_tool_expand_label, "Bash")))
                .performTouchInput { longClick() }
            compose.onNodeWithText(str(R.string.chat_copy_all)).performClick()
            assertEquals(output ?: input, clipboard())
        }
    }

    // Regression: holding a URL must offer URL-only actions rather than copying Markdown or the whole response.
    @Test
    fun longPressOffersCopyAndShareForTheLinkAlongsideMessageActions() {
        open()
        val url = "https://example.com/docs"
        show(ChatEntryBody.Agent("Read [Docs]($url)."), "Read Docs.")
        val node = compose.onNodeWithText("Read Docs.")
        val screenshot = java.io.File("build/reports/chat-links/message.png")
        screenshot.parentFile?.mkdirs()
        screenshot.outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
        var layout: TextLayoutResult? = null
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            action(results)
            layout = results.single()
        }
        node.performTouchInput { longClick(layout!!.getBoundingBox(6).center) }
        compose.onNodeWithText(str(R.string.chat_copy_all)).assertExists()
        compose.onNodeWithText(str(R.string.chat_link_copy, url)).performClick()
        assertEquals(url, clipboard())
        node.performTouchInput { longClick(layout!!.getBoundingBox(6).center) }
        compose.onNodeWithText(str(R.string.chat_link_share, url)).performClick()
        val chooser = org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val shared = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals(android.content.Intent.ACTION_SEND, shared.action)
        assertEquals(url, shared.getStringExtra(android.content.Intent.EXTRA_TEXT))
    }

    @Test
    fun aFileReferenceCanBeCopiedWithALongPressAndStillOpenedWithATap() {
        open()
        val reads = mutableListOf<Triple<String, String, ULong>>()
        core.connection!!.fileReader = { address, path, offset, _ ->
            reads += Triple(address, path, offset)
            // A short Host response must still fill the viewport after opening the link.
            RemoteFilePage("file contents $offset", offset, offset, 20u)
        }
        show(ChatEntryBody.Agent("README.md:7"))
        val reference = compose.onNodeWithText("README.md:7")
        reference.performTouchInput { longClick() }
        compose.onNodeWithText(str(R.string.chat_copy_all)).performClick()
        assertEquals("README.md:7", clipboard())
        assertEquals(emptyList<Triple<String, String, ULong>>(), reads)
        reference.performFirstLinkClick()
        compose.onNodeWithTag("files-line:7").assertIsDisplayed()
        compose.onNodeWithText("file contents 7").assertIsDisplayed()
        assertEquals(Triple("t1", "/repo/README.md", 7uL), reads.first())
        for ((address, path, line) in reads.toList()) {
            assertEquals("t1" to "/repo/README.md", address to path)
            compose.onNodeWithTag("files-line:$line").assertIsDisplayed()
        }
    }

    @Test
    fun selectTextCopiesJustAWordAndKeepsItsSourceWhileTheResponseStreams() {
        open()
        val text = "alpha beta gamma"
        show(ChatEntryBody.Agent(text))
        compose.onNodeWithText(text).performTouchInput { longClick() }
        compose.onNodeWithText(str(R.string.chat_select_text)).performClick()
        val selected = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
        selected.assertExists()
        // Selection uses a stable copy of the text, not the streaming preview.
        show(ChatEntryBody.Agent("alpha beta gamma delta"))
        selected.assertExists()
        var layout: TextLayoutResult? = null
        selected.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            action(results)
            layout = results.single()
        }
        selected.performTouchInput { longClick(layout!!.getBoundingBox(1).center) }
        compose.waitUntil(5_000) { textMenu != null }
        compose.runOnIdle {
            val copy = textMenu!!.data().components.filterIsInstance<TextContextMenuItem>()
                .single { it.key == TextContextMenuKeys.CopyKey }
            copy.onClick(object : TextContextMenuSession {
                override fun close() { menuClosed.complete(Unit) }
            })
        }
        compose.waitForIdle()
        assertEquals("alpha", clipboard())
        compose.onNode(hasText(str(R.string.back_button)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("alpha beta gamma delta").assertExists()
    }

    // Regression: disposing the originating lazy row must not discard an open selection.
    @Test
    fun selectionSurvivesRemovalOfItsTranscriptRow() {
        open()
        show(ChatEntryBody.Agent("Keep this selection"))
        compose.onNodeWithText("Keep this selection").performTouchInput { longClick() }
        compose.onNodeWithText(str(R.string.chat_select_text)).performClick()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("replacement", null, ChatEntryBody.Agent("New transcript"))), ChatStatus.LIVE, "new"))
        compose.waitForIdle()
        compose.onNode(hasText("Keep this selection") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        // D101: the frozen selection also survives Activity UI recreation after row replacement.
        compose.runOnIdle { generation.intValue++ }
        compose.onNode(hasText("Keep this selection") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        val shot = java.io.File("/tmp/aries-mvp-run/lanes/231-shots/selection.png")
        shot.parentFile?.mkdirs()
        shot.outputStream().use { output ->
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
        compose.onNode(hasText(str(R.string.back_button)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("New transcript").assertExists()
    }

    // D45: streaming must pause follow while a menu is open, and recycling its source must retain frozen actions.
    @Test
    fun textActionsPauseFollowingAndSurviveReplacementOfTheirSource() {
        open()
        val original = ChatEntry("entry", null, ChatEntryBody.Agent("Copy the original response"))
        show(original.body)
        compose.onNodeWithText("Copy the original response").performTouchInput { longClick() }
        val appended = (1..40).map { ChatEntry("new$it", null, ChatEntryBody.Agent("New reply $it\nMore output\nMore output")) }
        core.pushChat(ChatSnapshot(listOf(original) + appended, ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_copy_all)).assertIsDisplayed()
        compose.onNodeWithText("Copy the original response").assertIsDisplayed()

        core.pushChat(ChatSnapshot(appended, ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_copy_all)).performClick()
        assertEquals("Copy the original response", clipboard())
    }

    // A streaming row must freeze its current source on long press, rather than an earlier or later revision.
    @Test
    fun textActionsFreezeTheResponseAtTheMomentOfTheLongPress() {
        open()
        show(ChatEntryBody.Agent("Before the update"))
        show(ChatEntryBody.Agent("Copy this current response"))
        compose.onNodeWithText("Copy this current response").performTouchInput { longClick() }
        show(ChatEntryBody.Agent("This arrived after the long press"))
        compose.onNodeWithText(str(R.string.chat_copy_all)).performClick()
        assertEquals("Copy this current response", clipboard())
    }
}

/** Robolectric has no Surface for Android's selection magnifier to draw into. */
@Implements(Magnifier::class)
class ShadowChatMagnifier {
    @Implementation fun show(x: Float, y: Float) = Unit
    @Implementation fun show(x: Float, y: Float, magnifierX: Float, magnifierY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}
