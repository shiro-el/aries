package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToIndex
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

    private fun screenshot(name: String) {
        System.getenv("ARIES_FILES_RENDER_DIR")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun parentEntryBrowsesAboveTheStartingFolderToTheHostRoot() {
        val requests = mutableListOf<String?>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun gitChanges(address: String) = GitChanges("/home/me/repo", emptyList(), false, true)
            override suspend fun listFiles(address: String, path: String?): RemoteDirectory {
                assertEquals("box/t7", address)
                requests += path
                return RemoteDirectory(path ?: "/home/me/repo", emptyList())
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "box/t7", {}) } }
        screenshot("starting-folder")
        compose.onNodeWithText("..").performClick()
        compose.runOnIdle { assertEquals(listOf(null, "/home/me"), requests) }
        compose.onNodeWithText("..").performClick()
        compose.runOnIdle { assertEquals("/home", requests.last()) }
        compose.onNodeWithText("..").performClick()
        compose.runOnIdle { assertEquals(listOf(null, "/home/me", "/home", "/"), requests) }
        screenshot("root")
    }

    @Test fun toolbarAndSystemBackClimbFoldersAndCloseAtTheStartingFolder() {
        val requests = mutableListOf<String?>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun gitChanges(address: String) = GitChanges("/repo", emptyList(), false, true)
            override suspend fun listFiles(address: String, path: String?): RemoteDirectory {
                requests += path
                return when (path) {
                    null, "/repo" -> RemoteDirectory("/repo", listOf(RemoteFileEntry("src", true)))
                    "/repo/src" -> RemoteDirectory(path, listOf(RemoteFileEntry("deep", true)))
                    "/repo/src/deep" -> RemoteDirectory(path, listOf(RemoteFileEntry("a.kt", false)))
                    else -> error("Unexpected folder: $path")
                }
            }
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt) =
                RemoteFilePage("source", 1u, 1u, 1u)
        }
        var closed = 0
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            MaterialTheme { RemoteFilesScreen(conn, "box/t7", { closed++ }) }
        }
        compose.onNodeWithText("src/").performClick()
        compose.onNodeWithText("deep/").performClick()
        compose.onNodeWithText("a.kt").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.files_open_folder_label, "/repo/src/deep")).performClick()
        compose.runOnIdle { assertEquals("/repo/src/deep", requests.last()) }
        screenshot("nested-folder")
        compose.onNodeWithTag("files-back").performClick()
        compose.runOnIdle { assertEquals("/repo/src", requests.last()); assertEquals(0, closed) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals("/repo", requests.last()); assertEquals(0, closed) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun failedParentListingCanContinueUpWithoutReusingTheOldDirectory() {
        val requests = mutableListOf<String?>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun gitChanges(address: String) = GitChanges("/restricted/repo", emptyList(), false, true)
            override suspend fun listFiles(address: String, path: String?): RemoteDirectory {
                requests += path
                if (path == "/restricted") throw CoreException.Rpc("file_listing_failed", "Permission denied")
                return RemoteDirectory(path ?: "/restricted/repo", emptyList())
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "box/t7", {}) } }
        compose.onNodeWithText("..").performClick()
        compose.runOnIdle { assertEquals("/restricted", requests.last()) }
        screenshot("denied-parent")
        compose.onNodeWithText("..").performClick()
        compose.runOnIdle { assertEquals(listOf(null, "/restricted", "/"), requests) }
    }

    @Test fun browsesBeyondTwoHundredLinesAndReturnsTheLastLineForTheDraft() {
        val requests = mutableListOf<Triple<String, ULong, UInt>>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?): RemoteDirectory {
                assertEquals("box/t7", address)
                return if (path == null) RemoteDirectory("/repo", listOf(RemoteFileEntry("src", true)))
                else RemoteDirectory("/repo/src", listOf(RemoteFileEntry("a.kt", false)))
            }
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                requests += Triple(path, offset, limit)
                val last = minOf(605uL, offset + limit - 1u)
                val text = (offset..last).joinToString("\n") { if (it == 605uL) "val last = 605" else "line $it" }
                return RemoteFilePage(text, offset, last, 605u)
            }
        }
        var draft = ""
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "box/t7", {}, { draft = it }) } }
        compose.onNodeWithText("src/").performClick()
        compose.onNodeWithText("a.kt").performClick()
        compose.onNodeWithText("line 1").assertExists()
        compose.onNodeWithTag("files-lines").performScrollToIndex(604)
        compose.onNodeWithText("val last = 605").assertExists()
        screenshot("full-file-last-line")
        compose.onNodeWithTag("files-line:605").performClick()
        assertEquals("'/repo/src/a.kt':605\nval last = 605", draft)
        org.junit.Assert.assertTrue(requests.any { it.second > 200uL })

    }
    @Test fun changedFileLinkStartsWithDiffAndFullFileKeepsTheLinkedLine() {
        val reads = mutableListOf<ULong>()
        val diffs = mutableListOf<Triple<String, String, GitDiffKind>>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun gitChanges(address: String) = GitChanges("/repo",
                listOf(GitFile("src/Main.kt", null, "MM", true, true, false)), false, false)
            override suspend fun gitDiff(address: String, cwd: String, path: String, kind: GitDiffKind): GitDiff {
                assertEquals("box/t7", address)
                diffs += Triple(cwd, path, kind)
                return GitDiff(listOf(GitDiffLine("+updated", null, 415u)), false, false)
            }
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                assertEquals("box/t7", address)
                assertEquals("/repo/src/Main.kt", path)
                reads += offset
                val last = minOf(800uL, offset + limit - 1u)
                return RemoteFilePage((offset..last).joinToString("\n") { if (it == 415uL) "full source" else "line $it" }, offset, last, 800u)
            }
        }
        compose.setContent { MaterialTheme {
            RemoteFilesScreen(conn, "box/t7", {}, initialLocation = FileLocation("/repo/src/Main.kt", 415u))
        } }
        compose.onNodeWithText("+updated").assertExists()
        assertEquals(emptyList<ULong>(), reads)
        compose.onNodeWithContentDescription("Full file").performClick()
        compose.onNodeWithText("full source").assertExists()
        assertEquals(listOf(415uL), reads)
        compose.onNodeWithContentDescription("Diff").performClick()
        compose.onNodeWithText(str(R.string.git_staged)).performClick()
        compose.onNodeWithText("+updated").assertExists()
        assertEquals(Triple("/repo", "src/Main.kt", GitDiffKind.UNSTAGED), diffs.first())
        assertEquals(Triple("/repo", "src/Main.kt", GitDiffKind.STAGED), diffs.last())
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
        compose.onNodeWithContentDescription(str(R.string.files_back_folder_button)).performClick()
        binary = true
        compose.onNodeWithText("binary").performClick()
        compose.onNodeWithText(str(R.string.files_not_text)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
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
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithTag("files-open").performClick()
        compose.onNodeWithText("a'b.kt").performClick()
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_send_file_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("Please review\n'/repo/a'\\''b.kt'")
        assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)

        // The same action from Terminal keeps the previous Chat draft and appends another reference.
        compose.onNodeWithText(str(R.string.agent_view_terminal)).performClick()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithTag("files-open").performClick()
        compose.onNodeWithText("a'b.kt").performClick()
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
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
            val last = minOf(800uL, offset + 199u)
            RemoteFilePage((offset..last).joinToString("\n") { if (it == 415uL) "val answer = 42" else "line $it" }, offset, last, 800u)
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
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_close_button)).performClick()
        core.push(core.terminals.map { it.copy(foreground = ForegroundInfo(42u, "codex", listOf("codex"), "/current")) })
        core.pushChat(ChatSnapshot(listOf(ChatEntry("a2", null, ChatEntryBody.Agent("README.md"))), ChatStatus.LIVE, null))
        compose.waitUntil(5000) { compose.onAllNodesWithText("README.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("README.md").performFirstLinkClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { reads.size >= 2 }
        assertEquals(Triple("box/t7", "/current/README.md", 1uL), reads.last())
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText(str(R.string.files_close_button)).performClick()
        core.push(core.terminals.map { it.copy(cwd = null, foreground = null) })
        core.pushChat(ChatSnapshot(listOf(ChatEntry("a3", null, ChatEntryBody.Agent("/etc/hosts"))), ChatStatus.LIVE, null))
        compose.waitUntil(5000) { compose.onAllNodesWithText("/etc/hosts").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/etc/hosts").performFirstLinkClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { reads.size >= 3 }
        assertEquals(Triple("box/t7", "/etc/hosts", 1uL), reads.last())
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
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
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).assertExists()
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
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText("Wrap lines").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.waitUntil(5000) { layout().lineCount > 1 }
        org.junit.Assert.assertTrue(layout().layoutInput.text.spanStyles.isNotEmpty())
        compose.onNodeWithContentDescription(str(R.string.files_actions_label)).performClick()
        compose.onNodeWithText("Wrap lines").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.waitUntil(5000) { layout().lineCount == 1 }
    }

    @Test fun findReachesUnloadedLinesAndWrapsAcrossTheWholeFile() {
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                // The Host may return fewer lines than requested because of its byte budget.
                val last = minOf(1001uL, offset + 36u)
                val text = (offset..last).joinToString("\n") {
                    when (it) { 415uL -> "val needle = 415"; 1001uL -> "val needle = 1001"; else -> "line $it" }
                }
                return RemoteFilePage(text, offset, last, 1001u)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/repo/a.kt")) } }
        compose.onNodeWithText("val needle = 415").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.files_find_hint)).performTextInput("NEEDLE")
        fun found(text: String) {
            compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        found("val needle = 415")
        compose.onNodeWithTag("files-line:415").assertExists()
        compose.onNodeWithContentDescription(str(R.string.files_find_next)).performClick()
        found("val needle = 1001")
        compose.onNodeWithContentDescription(str(R.string.files_find_next)).performClick()
        found("val needle = 415")
        compose.onNodeWithContentDescription(str(R.string.files_find_previous)).performClick()
        found("val needle = 1001")
    }

    @Test fun largeTextNeedsAnExplicitOpenBeforeReadingItsLines() {
        var reads = 0
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFileBytes(address: String, path: String, offset: ULong, length: UInt) =
                RemoteFileBytes(byteArrayOf(97), 0u, 3uL * 1024u * 1024u, false)
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                reads++
                return RemoteFilePage("large source", 1u, 1u, 1u)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/repo/large.txt")) } }
        compose.onNodeWithText(str(R.string.files_large_warning)).assertExists()
        assertEquals(0, reads)
        compose.onNodeWithText(str(R.string.files_large_open)).performClick()
        compose.onNodeWithText("large source").assertExists()
        assertEquals(1, reads)
    }

}
