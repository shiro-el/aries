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
            model = remember { HostsModel(core, scope).apply { start() } }
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
            assertEquals(listOf("t2" to null), connection.fileListings)
            assertTrue(connection.typed.isEmpty())
            assertTrue(connection.launched.isEmpty())
        }
        compose.onNodeWithTag("task-row:h1:t1").assertExists()
    }
}
