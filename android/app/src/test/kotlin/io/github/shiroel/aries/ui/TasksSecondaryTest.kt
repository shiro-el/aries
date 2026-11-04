package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host tool routing is not covered by the standalone Files screen tests. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class TasksSecondaryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // D51/D52: reopening Host tools after cd must browse the new folder and
    // resolve GitHub again, even though the Terminal address has not changed.
    @Test fun reopenedHostToolsFollowForegroundFolderAndResolveTheNewRepository() {
        val terminal = TerminalInfo("t1", "Build", "", listOf("sh"), "/launch", "live",
            foreground = ForegroundInfo(42u, "sh", listOf("sh"), "/current"))
        val conn = FakeConnection(listOf(terminal), HostStructure(emptyList(), listOf(terminal), null))
        val host = Host("h1", "home", Endpoint.Ssh("home.test", 22u, "me"), null)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val browser = HostBrowser(host).apply {
            link = HostLink(host, conn, scope, { conn })
            updateTerminals(listOf(terminal))
        }
        compose.setContent {
            androidx.compose.material3.MaterialTheme { TasksHostContent(remember { HostsModel(FakeCore(), scope) }, browser) }
        }
        compose.onNodeWithTag("tasks-host-files").assertTextContains("/current")
        compose.onNodeWithTag("tasks-host-github").assertTextContains("/current")
        compose.onNodeWithTag("tasks-host-files").performClick()
        compose.runOnIdle { assertEquals(listOf("t1" to "/current"), conn.fileListings) }
        compose.onNodeWithContentDescription(context.getString(R.string.files_close_button)).performClick()
        compose.onNodeWithTag("tasks-host-github").performClick()
        compose.onNodeWithText("octo/project").assertExists()
        compose.onNodeWithTag("github-back").performClick()
        compose.runOnIdle {
            browser.updateTerminals(listOf(terminal.copy(foreground = terminal.foreground!!.copy(cwd = "/new"))))
            conn.githubRepo = GithubRepository("/new", null, "octo/new-project", "github.com")
        }
        compose.onNodeWithTag("tasks-host-github").assertTextContains("/new").performClick()
        compose.onNodeWithText("octo/new-project").assertExists()
        compose.onNodeWithText("octo/project").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, conn.githubRepoReads) }
        compose.onNodeWithTag("github-back").performClick()
        compose.onNodeWithTag("tasks-host-files").performClick()
        compose.runOnIdle {
            assertEquals("t1" to "/new", conn.fileListings.last())
            assertFalse(browser.link!!.github("t1").shown)
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // D52: Activity recreation must keep the pinned Terminal, open file and expanded folder.
    @Test fun hostFilesKeepTheirPinnedTargetAndLocationAcrossRecreation() {
        val terminal = TerminalInfo("t1", "Build", "", listOf("sh"), "/repo", "live")
        val other = terminal.copy(address = "t2", cwd = "/other")
        val reads = mutableListOf<Pair<String, String>>()
        val connection = object : HostConnection(NoHandle) {
            override suspend fun gitChanges(address: String) = GitChanges("/repo", emptyList(), false, false)
            override suspend fun listFiles(address: String, path: String?) = if (path == null || path == "/repo")
                RemoteDirectory("/repo", listOf(RemoteFileEntry("src", true)))
            else RemoteDirectory(path, listOf(RemoteFileEntry("notes.txt", false)))
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                reads += address to path
                val last = minOf(600uL, offset + limit - 1u)
                return RemoteFilePage((offset..last).joinToString("\n") {
                    if (it == 1uL) "reading pinned file" else "line $it"
                }, offset, last, 600u)
            }
        }
        val host = Host("h1", "home", Endpoint.Ssh("home.test", 22u, "me"), null)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val browser = HostBrowser(host).apply {
            link = HostLink(host, connection, scope, { connection })
            updateTerminals(listOf(terminal, other))
        }
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            androidx.compose.material3.MaterialTheme { TasksHostContent(remember { HostsModel(FakeCore(), scope) }, browser) }
        }
        compose.onNodeWithTag("tasks-host-files").performClick()
        compose.onNodeWithText("src/").performClick()
        compose.onNodeWithText("notes.txt").performClick()
        compose.onNodeWithText("reading pinned file").assertExists()
        compose.onNodeWithTag("files-lines").performScrollToIndex(450)
        compose.onNodeWithText("line 451").assertExists()
        // A new Host snapshot changes the default target while this panel is pinned.
        compose.runOnIdle { browser.updateTerminals(listOf(other, terminal)) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("line 451").assertExists()
        compose.runOnIdle { assertTrue(reads.all { it == "t1" to "/repo/src/notes.txt" }) }
        compose.onNodeWithTag("files-back").performClick()
        compose.onNodeWithText("notes.txt").assertExists()
        compose.onNodeWithTag("files-back").performClick()
        compose.onNodeWithTag("tasks-host-content").assertExists()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun hostFilesUseTheCurrentTerminalAndReturnToTheSameConnectionWithoutSending() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(
                TerminalInfo("t1", "Build queue", "", listOf("codex"), "/repo", "live", AgentStatus.Working),
                TerminalInfo("t2", "Review queue", "", listOf("claude"), "/review", "live", AgentStatus.Done))
            structure = HostStructure(listOf(SpaceInfo("s1", "Project", "/repo", listOf(TabInfo("tab1", "Main",
                listOf(PaneInfo("p1", "t1", terminals[0]), PaneInfo("p2", "t2", terminals[1])))))), emptyList(), null)
        }
        val prefs = context.getSharedPreferences("tasks-secondary-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = ThemeSettings(prefs).apply { chooseLayout(AppLayout.TASKS) }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope).apply { start(layout = AppLayout.TASKS) } }
            AriesApp(model, settings)
        }
        compose.onNodeWithTag("tasks-hosts").performClick()
        compose.onNodeWithText("home").performClick()
        val original = model.screen as Screen.Browse
        val connection = original.browser.link!!.connection
        compose.runOnIdle {
            original.browser.openSpace("s1")
            val tab = original.browser.tab!!
            original.browser.focus(tab, tab.panes.last())
        }
        compose.onNodeWithTag("tasks-host-files").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.files_close_button)).performClick()
        compose.runOnIdle {
            assertSame(original, model.screen)
            assertSame(connection, original.browser.link!!.connection)
            assertFalse((connection as FakeConnection).disconnected)
            assertEquals(listOf("t2" to "/review"), connection.fileListings)
            assertTrue(connection.typed.isEmpty())
            assertTrue(connection.launched.isEmpty())
        }
        compose.onNodeWithTag("task-row:h1:t1").assertExists()
    }
}
