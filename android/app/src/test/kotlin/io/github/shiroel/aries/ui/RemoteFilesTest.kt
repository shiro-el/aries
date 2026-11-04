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
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
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

    // Regression: delayed git status must not block browsing or opening files.
    @Test fun filesRemainUsableWhileGitIsPending() {
        val git = kotlinx.coroutines.CompletableDeferred<GitChanges>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun gitChanges(address: String) = git.await()
            override suspend fun listFiles(address: String, path: String?) =
                RemoteDirectory("/repo", listOf(RemoteFileEntry("a.txt", false)))
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt) =
                RemoteFilePage("ready before git", 1u, 1u, 1u)
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}) } }
        compose.onNodeWithText("a.txt").performClick()
        compose.onNodeWithText("ready before git").assertExists()
        compose.runOnIdle { git.complete(GitChanges("/repo", emptyList(), false, false)) }
    }

    // Regression: inline retry must recover when the Host changes a file's line count.
    @Test fun inlineRefreshReloadsTheFileAfterItsLineCountChanges() {
        var total = 500uL
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                val last = minOf(total, offset + limit - 1u)
                return RemoteFilePage((offset..last).joinToString("\n") { "line $it" }, offset, last, total)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/a.txt")) } }
        compose.onNodeWithText("line 1").assertExists()
        compose.runOnIdle { total = 600uL }
        compose.onNodeWithTag("files-lines").performScrollToIndex(400)
        screenshot("file-changed-refresh")
        compose.onNodeWithText(str(R.string.files_refresh_button)).performClick()
        compose.onNodeWithText("line 1").assertExists()
        compose.onNodeWithTag("files-lines").performScrollToIndex(599)
        compose.onNodeWithText("line 600").assertExists()
    }

    // Regression: opening a file must preserve the expanded tree and scroll on Back without another listing.
    @Test fun backFromFileRestoresExpandedTreeAndScroll() {
        var listings = 0
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?): RemoteDirectory {
                listings++
                return if (path == null) RemoteDirectory("/repo", listOf(RemoteFileEntry("src", true)))
                else RemoteDirectory(path, (1..50).map { RemoteFileEntry("file-$it.txt", false) })
            }
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt) =
                RemoteFilePage("source", 1u, 1u, 1u)
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}) } }
        compose.onNodeWithText("src/").performClick()
        compose.onNodeWithTag("files-tree").performScrollToIndex(35)
        compose.onNodeWithText("file-33.txt").performClick()
        compose.onNodeWithTag("files-back").performClick()
        compose.onNodeWithText("file-33.txt").assertExists()
        compose.runOnIdle { assertEquals(2, listings) }
        screenshot("tree-restored")
    }

    // Regression: Up then Back must return to the project instead of walking toward /.
    @Test fun backAfterParentEntryReturnsToThePreviouslyVisitedFolder() {
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?) =
                RemoteDirectory(path ?: "/home/me/repo", listOf(RemoteFileEntry(if (path == null) "project.txt" else "parent.txt", false)))
        }
        var closed = false
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", { closed = true }) } }
        compose.onNodeWithText("..").performClick()
        compose.onNodeWithText("parent.txt").assertExists()
        compose.onNodeWithTag("files-back").performClick()
        compose.onNodeWithText("project.txt").assertExists()
        compose.onNodeWithTag("files-back").performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }

    // Regression: search must start at the viewport and reuse scanned ranges when stepping backwards.
    @Test fun findStartsAtTheVisibleLineAndReusesItsIndex() {
        val reads = mutableListOf<ULong>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                reads += offset
                val last = minOf(1200uL, offset + limit - 1u)
                return RemoteFilePage((offset..last).joinToString("\n") {
                    if (it in listOf(750uL, 900uL)) "needle $it" else "line $it"
                }, offset, last, 1200u)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/a.txt", 700u)) } }
        compose.onNodeWithText("line 700").assertExists()
        compose.onNodeWithText(str(R.string.files_find_hint)).performTextInput("needle")
        compose.waitUntil(5000) { compose.onAllNodesWithText("needle 750").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(str(R.string.files_find_next)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("needle 900").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(str(R.string.files_find_previous)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("needle 750").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { org.junit.Assert.assertTrue(reads.all { it >= 700uL }) }
    }

    // Regression: comments spanning a page boundary must not turn into executable-looking code.
    @Test fun blockCommentHighlightContinuesAcrossPageBoundary() {
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                val last = minOf(400uL, offset + limit - 1u)
                return RemoteFilePage((offset..last).joinToString("\n") {
                    when (it) { 1uL -> "/*"; 201uL -> "val hidden = 1"; 202uL -> "*/"; else -> "comment" }
                }, offset, last, 400u)
            }
        }
        lateinit var colors: androidx.compose.material3.ColorScheme
        compose.setContent { MaterialTheme { colors = MaterialTheme.colorScheme; RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/a.kt")) } }
        compose.onNodeWithText("1", substring = false).assertExists()
        compose.onNodeWithTag("files-lines").performScrollToIndex(200)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("val hidden = 1").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        org.junit.Assert.assertEquals(colors.onSurfaceVariant, results.single().layoutInput.text.spanStyles.first().item.color)
    }

    // Regression: Files must observe connection replacement while its dialog stays open, without a null-connection error.
    @Test fun openFilesRecoversAfterTheHostReconnects() {
        val core = FakeCore().apply {
            terminals = listOf(TerminalInfo("t1", "agent", "", listOf("codex"), "/repo", "live", AgentStatus.Idle))
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("example.invalid", 22u, "me"))
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            model = androidx.compose.runtime.remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("agent").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        val original = core.connection!!
        original.fileDirectory = RemoteDirectory("/repo", listOf(RemoteFileEntry("before.txt", false)))
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithTag("files-open").performClick()
        compose.onNodeWithText("before.txt").assertExists()
        val link = compose.runOnIdle { (model.screen as Screen.Chat).chat.link!! }
        compose.runOnIdle { link.pause() }
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText("Required value was null.", substring = true).assertDoesNotExist()
        screenshot("files-reconnecting")
        compose.runOnIdle { link.resume() }
        compose.onNodeWithText(str(R.string.files_empty_folder)).assertExists()
        compose.onNodeWithText("before.txt").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertNotSame(original, core.connection) }
    }

    @Test fun parentEntryBrowsesAboveTheStartingFolderToTheHostRoot() {
        val requests = mutableListOf<String?>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun gitChanges(address: String) = GitChanges("/home/me/repo", emptyList(), false, true)
            override suspend fun listFiles(address: String, path: String?): RemoteDirectory {
                assertEquals("box/t7", address)
                requests += path
                val entries = if (path == null) listOf(RemoteFileEntry("src", true), RemoteFileEntry("README.md", false)) +
                    (1..30).map { RemoteFileEntry("file-$it.txt", false) } else emptyList()
                return RemoteDirectory(path ?: "/home/me/repo", entries)
            }
        }
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "box/t7", {}) } }
        screenshot("starting-folder")
        if (System.getenv("ARIES_FILES_RENDER_DIR") != null) {
            compose.onNodeWithTag("files-tree").performScrollToIndex(20)
            screenshot("scrolled-folder")
            compose.onNodeWithTag("files-tree").performScrollToIndex(0)
        }
        compose.onNodeWithText("..").performClick()
        compose.runOnIdle { assertEquals(listOf(null, "/home/me"), requests) }
        compose.onNodeWithText("..").performClick()
        compose.runOnIdle { assertEquals("/home", requests.last()) }
        compose.onNodeWithText("..").performClick()
        compose.runOnIdle { assertEquals(listOf(null, "/home/me", "/home", "/"), requests) }
        screenshot("root")
    }

    @Test fun toolbarAndSystemBackReturnThroughVisitedFoldersThenClose() {
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
        compose.onNodeWithText("src/").assertExists()
        compose.runOnIdle { assertEquals(0, closed) }
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
        val diffLine = "+updated " + "long comment ".repeat(20)
        val sourceLine = "full source // " + "long comment ".repeat(20)
        val reads = mutableListOf<ULong>()
        val diffs = mutableListOf<Triple<String, String, GitDiffKind>>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun gitChanges(address: String) = GitChanges("/repo",
                listOf(GitFile("src/Main.kt", null, "MM", true, true, false)), false, false)
            override suspend fun gitDiff(address: String, cwd: String, path: String, kind: GitDiffKind): GitDiff {
                assertEquals("box/t7", address)
                diffs += Triple(cwd, path, kind)
                return GitDiff(listOf(GitDiffLine(diffLine, null, 415u)), false, false)
            }
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                assertEquals("box/t7", address)
                assertEquals("/repo/src/Main.kt", path)
                reads += offset
                val last = minOf(800uL, offset + limit - 1u)
                return RemoteFilePage((offset..last).joinToString("\n") { if (it == 415uL) sourceLine else "line $it" }, offset, last, 800u)
            }
        }
        compose.setContent { MaterialTheme {
            RemoteFilesScreen(conn, "box/t7", {}, initialLocation = FileLocation("/repo/src/Main.kt", 415u))
        } }
        fun lineCount(text: String): Int {
            // The file switch loads and lays out the requested line asynchronously.
            if (compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) return 0
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().lineCount
        }
        assertEquals(1, lineCount(diffLine))
        // Source can load immediately while Git status resolves.
        val readsBeforeFullFile = reads.size
        screenshot("wrap-diff-off")
        compose.onNodeWithContentDescription(str(R.string.files_wrap_lines)).performClick()
        compose.waitUntil(5000) { lineCount(diffLine) > 1 }
        screenshot("wrap-diff-on")
        compose.onNodeWithContentDescription("Full file").performClick()
        compose.waitUntil(5000) { lineCount(sourceLine) > 1 }
        assertEquals(415uL, reads.last())
        assertEquals(readsBeforeFullFile + 1, reads.size)
        compose.onNodeWithContentDescription("Diff").performClick()
        compose.onNodeWithText(str(R.string.git_staged)).performClick()
        compose.waitUntil(5000) { lineCount(diffLine) > 1 }
        compose.onNodeWithContentDescription(str(R.string.files_wrap_lines)).assertIsOn().performClick()
        compose.waitUntil(5000) { lineCount(diffLine) == 1 }
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
        compose.onNodeWithContentDescription(str(R.string.agent_switch_terminal_label)).performClick()
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

    @Test fun toolbarWrapsCodeAndRestoresTheChoiceWhenTheViewerReopens() {
        val line = "val answer = 42 // " + "long comment ".repeat(20)
        val conn = object : HostConnection(NoHandle) {
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt) =
                RemoteFilePage(line, 1u, 1u, 1u)
        }
        var generation by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent { androidx.compose.runtime.key(generation) {
            MaterialTheme { RemoteFilesScreen(conn, "t1", {}, initialLocation = FileLocation("/a.kt")) }
        } }
        fun layout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(line).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        assertEquals(1, layout().lineCount)
        compose.onNodeWithContentDescription(str(R.string.files_wrap_lines)).assertIsOff()
        screenshot("wrap-file-off")
        compose.onNodeWithContentDescription(str(R.string.files_wrap_lines)).performClick()
        compose.waitUntil(5000) { layout().lineCount > 1 }
        compose.onNodeWithContentDescription(str(R.string.files_wrap_lines)).assertIsOn()
        org.junit.Assert.assertTrue(layout().layoutInput.text.spanStyles.isNotEmpty())
        screenshot("wrap-file-on")
        compose.runOnIdle { generation++ }
        compose.waitUntil(5000) { layout().lineCount > 1 }
        compose.onNodeWithContentDescription(str(R.string.files_wrap_lines)).assertIsOn().performClick()
        compose.waitUntil(5000) { layout().lineCount == 1 }
        compose.runOnIdle { generation++ }
        compose.waitUntil(5000) { layout().lineCount == 1 }
        compose.onNodeWithContentDescription(str(R.string.files_wrap_lines)).assertIsOff()
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
        compose.onNodeWithText(str(R.string.files_find_hint)).performImeAction()
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
