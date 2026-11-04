package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class TasksChangesInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val terminal = TerminalInfo("t1", "Agent", "", listOf("codex"), "/repo", "live", AgentStatus.Done)
    private val conn = FakeConnection(listOf(terminal), HostStructure(emptyList(), listOf(terminal), null)).apply {
        gitFiles = GitChanges("/repo", listOf(GitFile("file.kt", null, " M", false, true, false)), false, false)
        gitPatch = GitDiff(listOf(GitDiffLine("+hello", null, 1u)), false, false)
        gitTotals = GitStats(1u, 0u, false, false)
    }
    // Like HostsViewModel, this owner survives Activity composition recreation.
    private val state = TasksChangesState()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** D48: while the new folder is delayed, old rows cannot open diffs or append stale references. */
    @Test fun previousFolderDisablesDiffAndSendUntilTheNewFolderArrives() {
        var current by mutableStateOf(terminal)
        var sends = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val link = remember { HostLink(Host("h", "Host", Endpoint.Ssh("unused", 22u, "me"), null), conn, scope, { conn }) }
            MaterialTheme { Column(Modifier.fillMaxSize()) { TasksChanges(state, link, current) { sends++ } } }
        }
        compose.onNodeWithText("file.kt").performClick()
        compose.onNodeWithText("+hello").assertExists()
        compose.runOnIdle {
            conn.gitChangesHold = CompletableDeferred()
            conn.gitFiles = GitChanges("/new", listOf(GitFile("new.kt", null, " M", false, true, false)), false, false)
            current = terminal.copy(cwd = "/new")
        }
        compose.onNodeWithText(context.getString(R.string.tasks_changes_previous_folder)).assertExists()
        compose.onNodeWithText("file.kt").assertIsNotEnabled().performClick()
        compose.onNodeWithText("+hello").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.git_send_file)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, conn.gitDiffRequests.size)
            assertEquals(0, sends)
            conn.gitChangesHold!!.complete(Unit)
        }
        compose.onNodeWithText("new.kt").performClick()
        compose.onNodeWithText("+hello").assertExists()
        compose.runOnIdle { assertEquals("/new" to "new.kt", conn.gitDiffRequests.last()) }
    }

    /** D102: restoring the Activity composition must reopen the selected file; D51: no patch is fetched for totals. */
    @Test fun restoredCompositionKeepsTheOpenFileAndLoadsPatchesOnlyOnOpening() {
        conn.gitStatsHold = CompletableDeferred()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val scope = rememberCoroutineScope()
            val link = remember { HostLink(Host("h", "Host", Endpoint.Ssh("unused", 22u, "me"), null), conn, scope, { conn }) }
            MaterialTheme { Column(Modifier.fillMaxSize()) { TasksChanges(state, link, terminal) {} } }
        }
        compose.onNodeWithText("file.kt").assertExists()
        compose.runOnIdle {
            assertTrue(conn.gitDiffRequests.isEmpty())
            assertEquals(1, conn.gitStatsRequests)
        }
        compose.onNodeWithText("file.kt").performClick()
        compose.onNodeWithText("+hello").assertExists()
        // A delayed aggregate must not hold up the file list or a requested patch.
        compose.runOnIdle { conn.gitStatsHold!!.complete(Unit) }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("+hello").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.git_send_file)).assertExists()
        compose.runOnIdle { assertEquals(1, conn.gitChangesRequests) }
    }
}
