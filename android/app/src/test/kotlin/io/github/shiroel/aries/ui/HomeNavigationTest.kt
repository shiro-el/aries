package io.github.shiroel.aries.ui

import android.content.Context
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.PushTarget
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
class HomeNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var model: HostsModel
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var settings: ThemeSettings
    private var exits = 0
    private val core = FakeCore().apply {
        addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
        ensureKey()
        terminals = listOf(TerminalInfo("t1", "Working task", "", listOf("codex"), "/repo", "live", AgentStatus.Working))
        terminalsOf["h1"] = terminals
    }

    private fun show(layout: AppLayout = AppLayout.TASKS, target: PushTarget? = null) {
        val preferences = context.getSharedPreferences("home-navigation", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        settings = ThemeSettings(preferences).apply { chooseLayout(layout) }
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            DisposableEffect(back) {
                // Stand in for the Activity's exit fallback, below the screen handlers.
                val fallback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { exits++ }
                }
                back.addCallback(fallback)
                onDispose { fallback.remove() }
            }
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope).apply { start(target, layout = layout) } }
            AriesApp(model, settings)
        }
        compose.waitForIdle()
        compose.waitUntil { core.connectionsOf.containsKey("h1") }
        compose.waitForIdle()
    }

    // D03: system Back at Tasks root must reach the Activity fallback, not cycle through Hosts.
    @Test fun tasksRootBackExitsWithoutDisconnectingOrNavigatingToHosts() {
        show()
        val home = model.screen
        val connection = core.connectionsOf.getValue("h1")
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(1, exits)
            assertSame(home, model.screen)
            assertFalse(connection.disconnected)
        }
    }

    // D55: visiting management and a Host must keep the home watch alive and avoid reconnecting it.
    @Test fun visitingHostsAndAHostKeepsTheBoardAndItsLiveConnections() {
        show()
        val home = model.screen as Screen.Agents
        val connection = core.connectionsOf.getValue("h1")
        val attempts = core.connectAttempts.toList()
        compose.onNodeWithTag("tasks-hosts").performClick()
        compose.runOnIdle {
            assertEquals(Screen.Hosts, model.screen)
            assertFalse(connection.disconnected)
            core.push("h1", core.terminals.map { it.copy(name = "Updated while away") })
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertSame(home, model.screen)
            assertEquals(attempts, core.connectAttempts)
            assertEquals("Updated while away", home.board.agents.single().terminal.name)
        }
        compose.onNodeWithTag("tasks-hosts").performClick()
        compose.runOnIdle { model.openHost(model.hosts.single()) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Browse)
            assertFalse(connection.disconnected)
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertSame(home, model.screen)
            assertFalse(connection.disconnected)
            assertFalse(connection.watchStopped)
            // One additional Browse line is allowed; the retained home line never reconnects.
            assertEquals(attempts + "h1", core.connectAttempts)
        }
        compose.onNodeWithTag("tasks-hosts").performClick()
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.runOnIdle {
            assertSame(home, (model.screen as Screen.Chat).returnTo)
            assertSame(connection, (model.screen as Screen.Chat).chat.link?.connection)
            assertEquals(attempts + "h1", core.connectAttempts)
        }
        compose.runOnIdle { model.closeChat() }
        compose.onNodeWithTag("tasks-hosts").performClick()
        compose.onNodeWithTag("host-new-task").performClick()
        compose.runOnIdle {
            assertFalse("Starting a task from Hosts must keep the home line", connection.disconnected)
            model.dismissTask()
            model.openAgents()
            assertSame(home, model.screen)
        }
    }

    // D54: detail navigation used to dispose the adaptive list's entire saveable-state owner.
    @Test fun openingATaskPreservesFilterGroupingAndScrollOnReturn() {
        core.terminalsOf["h1"] = (1..35).map { index ->
            core.terminals.single().copy(address = "t$index", name = "Task $index")
        }
        show()
        compose.onNodeWithTag("tasks-filter-working").performClick()
        compose.onNodeWithTag("tasks-view-toggle").performClick()
        val list = compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag("tasks-home")))
        list.performScrollToNode(hasTestTag("agent-row:h1:t1"))
        val scrollBefore = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(scrollBefore > 0f)
        compose.onNodeWithTag("agent-row:h1:t1").performClick()
        compose.runOnIdle { back.onBackPressed() }
        val scrollAfter = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(scrollBefore, scrollAfter, 0.01f)
        list.performScrollToNode(hasTestTag("tasks-filter-working"))
        compose.onNodeWithTag("tasks-filter-working").assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.tasks_list_view)).assertExists()
    }

    // D105: home-origin Settings must return to the new layout's home in every direction.
    @Test fun changingLayoutInSettingsMapsTheHomeReturnRoute() {
        show()
        for (layout in listOf(AppLayout.CONSOLE, AppLayout.THREAD, AppLayout.TASKS,
            AppLayout.THREAD, AppLayout.CONSOLE, AppLayout.TASKS)) {
            compose.runOnIdle { model.openSettings() }
            val label = context.getString(when (layout) {
                AppLayout.CONSOLE -> R.string.layout_console
                AppLayout.THREAD -> R.string.layout_thread
                AppLayout.TASKS -> R.string.layout_tasks
            })
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.runOnIdle { back.onBackPressed() }
            compose.runOnIdle {
                assertEquals(layout, settings.layout)
                if (layout == AppLayout.CONSOLE) assertEquals(Screen.Hosts, model.screen)
                else assertTrue(model.screen is Screen.Agents)
            }
        }
    }

    // D55: a delayed first list must retain stored tasks as stale, then replace them with live data.
    @Test fun aNewBoardShowsLastKnownTasksUntilTheFreshInventoryArrives() {
        val firstList = CompletableDeferred<Unit>()
        core.firstTerminalListHolds["h1"] = firstList
        core.storedStates["h1"] = LastKnownState("h1",
            listOf(core.terminals.single().copy(address = "old/t1", name = "Previously known task")), 1000uL)
        show()
        compose.onNodeWithTag("task-row:h1:old/t1").assertIsDisplayed()
        compose.runOnIdle {
            val cached = (model.screen as Screen.Agents).board.agents.single()
            assertFalse(cached.live)
            assertEquals(1000L, cached.lastUpdatedMs)
            firstList.complete(Unit)
        }
        compose.onNodeWithTag("task-row:h1:t1").assertIsDisplayed()
        compose.onNodeWithTag("task-row:h1:old/t1").assertDoesNotExist()
        compose.runOnIdle { assertTrue((model.screen as Screen.Agents).board.agents.single().live) }
    }

    // D56: a notification's one-Host board must offer a route back to every registered Host.
    @Test fun aNotificationScopedHomeCanShowEveryHost() {
        core.addHost("Second", Endpoint.Ssh("second.test", 22u, "me"))
        core.terminalsOf["h2"] = listOf(core.terminals.single().copy(name = "Second Host task"))
        show(target = PushTarget("h1", "", agents = true))
        compose.runOnIdle {
            assertEquals(listOf("h1"), core.connectAttempts)
            assertEquals(listOf("h1"), (model.screen as Screen.Agents).board.hosts.map { it.id })
        }
        compose.onNodeWithTag("agents-show-all").performClick()
        compose.onNodeWithTag("task-row:h2:t1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(setOf("h1", "h2"), (model.screen as Screen.Agents).board.hosts.map { it.id }.toSet())
        }
        compose.onNodeWithTag("agents-show-all").assertDoesNotExist()
    }
}
