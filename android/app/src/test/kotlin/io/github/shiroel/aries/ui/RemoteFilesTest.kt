package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RemoteFilesTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    @Test fun browsesFoldersPagesAndReturnsALineForTheDraft() {
        val requests = mutableListOf<Triple<String, ULong, UInt>>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?): RemoteDirectory {
                assertEquals("box/t7", address)
                return if (path == null) RemoteDirectory("/repo", listOf(RemoteFileEntry("src", true)))
                else RemoteDirectory("/repo/src", listOf(RemoteFileEntry("a.kt", false)))
            }
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                requests += Triple(path, offset, limit)
                return if (offset == 1uL) RemoteFilePage("first\nsecond\n", 1u, 2u, 3u)
                else RemoteFilePage("last", 3u, 3u, 3u)
            }
        }
        var draft = ""
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "box/t7", {}, { draft = it }) } }
        compose.onNodeWithText("src/").performClick()
        compose.onNodeWithText("a.kt").performClick()
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText(str(R.string.files_next_button)).performClick()
        compose.onNodeWithText("last").assertExists()
        compose.onNodeWithTag("files-line:3").performClick()
        assertEquals("'/repo/src/a.kt':3\nlast", draft)
        assertEquals(listOf(Triple("/repo/src/a.kt", 1uL, 200u), Triple("/repo/src/a.kt", 3uL, 200u)), requests)
    }
    @Test fun nonTextAndNulBytesHaveNoPreviewButFileCanBeReferenced() {
        var binary = false
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?) =
                RemoteDirectory("/repo", listOf(RemoteFileEntry("binary", false)))
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                if (!binary) throw CoreException.Rpc("not_text", "not UTF-8")
                return RemoteFilePage("abc\u0000def", 1u, 1u, 1u)
            }
        }
        var draft = ""
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, { draft = it }) } }
        compose.onNodeWithText("binary").performClick()
        compose.onNodeWithText(str(R.string.files_not_text)).assertExists()
        compose.onNodeWithText(str(R.string.files_back_folder_button)).performClick()
        binary = true
        compose.onNodeWithText("binary").performClick()
        compose.onNodeWithText(str(R.string.files_not_text)).assertExists()
        compose.onNodeWithText(str(R.string.files_send_file_button)).performClick()
        assertEquals("'/repo/binary'", draft)
    }

    @Test fun sendingAFileAppendsToTheExistingChatDraftWithoutSending() {
        val core = FakeCore().apply {
            terminals = listOf(TerminalInfo("t1", "agent", "", listOf("codex"), "/repo", "live", AgentStatus.Idle))
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("example.invalid", 22u, "me"))
        }
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val model = androidx.compose.runtime.remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("agent").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("Please review")
        core.connection!!.fileDirectory = RemoteDirectory("/repo", listOf(RemoteFileEntry("a'b.kt", false)))
        compose.onNodeWithTag("files-open").performClick()
        compose.onNodeWithText("a'b.kt").performClick()
        compose.onNodeWithText(str(R.string.files_send_file_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("Please review\n'/repo/a'\\''b.kt'")
        assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)

        // The same action from Terminal keeps the previous Chat draft and appends another reference.
        compose.onNodeWithText(str(R.string.agent_view_terminal)).performClick()
        compose.onNodeWithTag("files-open").performClick()
        compose.onNodeWithText("a'b.kt").performClick()
        compose.onNodeWithText(str(R.string.files_send_file_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("Please review\n'/repo/a'\\''b.kt'\n'/repo/a'\\''b.kt'")
        assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)
    }

    @Test fun chatFileLinkOpensTheRequestedLineOnTheTerminalsMachine() {
        val core = FakeCore().apply {
            terminals = listOf(TerminalInfo("box/t7", "agent", "", listOf("codex"), "/repo", "live", AgentStatus.Idle))
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("example.invalid", 22u, "me"))
        }
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val model = androidx.compose.runtime.remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("agent").performClick()
        val reads = mutableListOf<Triple<String, String, ULong>>()
        core.connection!!.fileReader = { address, path, offset, _ ->
            reads += Triple(address, path, offset)
            RemoteFilePage("val answer = 42\n", offset, offset, 800u)
        }
        core.pushChat(ChatSnapshot(listOf(ChatEntry("a1", null, ChatEntryBody.Agent("See src/Main.kt:415."))), ChatStatus.LIVE, null))
        compose.waitUntil(5000) { compose.onAllNodesWithText("See src/Main.kt:415.").fetchSemanticsNodes().isNotEmpty() }
        val text = compose.onNodeWithText("See src/Main.kt:415.")
        var layout: TextLayoutResult? = null
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            action(results)
            layout = results.single()
        }
        text.performTouchInput { click(layout!!.getBoundingBox(6).center) }
        compose.onNodeWithTag("files-line:415").assertExists()
        compose.onNodeWithText("val answer = 42").assertExists()
        assertEquals(listOf(Triple("box/t7", "/repo/src/Main.kt", 415uL)), reads)
        compose.onNodeWithText(str(R.string.files_close_button)).performClick()
        core.push(core.terminals.map { it.copy(foreground = ForegroundInfo(42u, "codex", listOf("codex"), "/current")) })
        core.pushChat(ChatSnapshot(listOf(ChatEntry("a2", null, ChatEntryBody.Agent("README.md"))), ChatStatus.LIVE, null))
        compose.waitUntil(5000) { compose.onAllNodesWithText("README.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("README.md").performFirstLinkClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { reads.size >= 2 }
        assertEquals(Triple("box/t7", "/current/README.md", 1uL), reads.last())
        compose.onNodeWithText(str(R.string.files_close_button)).performClick()
        core.push(core.terminals.map { it.copy(cwd = null, foreground = null) })
        core.pushChat(ChatSnapshot(listOf(ChatEntry("a3", null, ChatEntryBody.Agent("/etc/hosts"))), ChatStatus.LIVE, null))
        compose.waitUntil(5000) { compose.onAllNodesWithText("/etc/hosts").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/etc/hosts").performFirstLinkClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { reads.size >= 3 }
        assertEquals(Triple("box/t7", "/etc/hosts", 1uL), reads.last())
        compose.onNodeWithText(str(R.string.files_close_button)).performClick()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("a4", null, ChatEntryBody.Agent("missing.kt"))), ChatStatus.LIVE, null))
        compose.waitUntil(5000) { compose.onAllNodesWithText("missing.kt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("missing.kt").performFirstLinkClick()
        compose.onNodeWithText(str(R.string.files_no_folder)).assertExists()
        assertEquals(3, reads.size)

    }

    @Test fun missingFileAndStaleLineHaveGuidanceAndKeepTheViewerUsable() {
        var target by androidx.compose.runtime.mutableStateOf(FileLocation("/repo/missing.kt"))
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                if (path.endsWith("missing.kt")) throw CoreException.Rpc("not_found", "missing")
                if (offset > 3uL) throw CoreException.Rpc("invalid_params", "offset beyond end")
                return RemoteFilePage("first\nsecond\nthird", 1u, 3u, 3u)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = target) } }
        compose.onNodeWithText("File not found: /repo/missing.kt").assertExists()
        compose.runOnIdle { target = FileLocation("/repo/a.kt", 35u) }
        compose.onNodeWithText("Line 35 is no longer in this file. Showing the start.").assertExists()
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText(str(R.string.files_close_button)).assertExists()
    }

    @Test fun codeCanWrapWithoutChangingItsText() {
        val line = "val answer = 42 // " + "long comment ".repeat(20)
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt) =
                RemoteFilePage(line, 1u, 1u, 1u)
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/a.kt")) } }
        fun layout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(line).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        assertEquals(1, layout().lineCount)
        compose.onNodeWithText("Wrap lines").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.waitUntil(5000) { layout().lineCount > 1 }
        org.junit.Assert.assertTrue(layout().layoutInput.text.spanStyles.isNotEmpty())
        compose.onNodeWithText("Wrap lines").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.waitUntil(5000) { layout().lineCount == 1 }
    }

}
