package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.AriesApplication
import io.github.shiroel.aries.HostsViewModel
import io.github.shiroel.aries.MainActivity
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

class TasksNavigationApplication : AriesApplication() {
    override val core = FakeCore().apply {
        ensureKey()
        addHost("Development", Endpoint.Ssh("home.test", 22u, "me"))
        terminals = listOf(TerminalInfo("t1", "Review navigation", "", listOf("codex"), "/repo", "live", AgentStatus.Working))
    }
}

/** Real Activity recreation and system Back at the Android UI/core seam. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TasksNavigationApplication::class, qualifiers = "h1200dp")
class TasksShellNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<TasksNavigationApplication>()
    private var activity: ActivityController<MainActivity>? = null
    private val model get() = ViewModelProvider(activity!!.get())[HostsViewModel::class.java].model

    private fun show(location: LastLocation? = null) {
        app.getSharedPreferences("app-guidance", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        ThemeSettings.from(app).apply {
            chooseLayout(AppLayout.TASKS)
            chooseMode(ThemeMode.LIGHT)
        }
        val locations = app.getSharedPreferences("last_location", Context.MODE_PRIVATE)
        locations.edit().clear().commit()
        location?.let { LastLocationStore(locations).save(it) }
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
    }

    @After fun closeActivity() { activity?.pause()?.stop()?.destroy() }

    // D115: delayed cold Chat restoration must build an all-Host home as the Back destination.
    @Test fun coldChatRestoresOverTheHomeBoardEvenWhenTheDefaultViewIsTerminal() {
        app.core.addHost("Second", Endpoint.Ssh("second.test", 22u, "me"))
        app.core.terminalsOf["h2"] = app.core.terminals.map { it.copy(name = "Other task") }
        val firstList = CompletableDeferred<Unit>()
        app.core.firstTerminalListHolds["h1"] = firstList
        show(LastLocation("h1", terminal = "t1", view = AgentView.CHAT))
        val home = model.screen as Screen.Agents
        compose.onNodeWithTag("tasks-home").assertIsDisplayed()
        compose.runOnIdle {
            model.setDefaultAgentView(AgentView.TERMINAL)
            firstList.complete(Unit)
        }
        compose.waitUntil(5_000) { model.screen is Screen.Chat }
        compose.runOnIdle {
            val detail = model.screen as Screen.Chat
            assertEquals("t1", detail.chat.terminal.address)
            assertSame(home, detail.returnTo)
            assertEquals(AgentView.TERMINAL, model.agentView)
        }
        compose.runOnIdle { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertSame(home, model.screen) }
        compose.onNodeWithTag("task-row:h1:t1").assertIsDisplayed()
        compose.onNodeWithTag("task-row:h2:t1").assertIsDisplayed()
    }

    // D115: sharing Thread's always-Chat restore would lose a saved Terminal view in Tasks.
    @Test fun coldTerminalRestoresOverHomeEvenWhenTheDefaultViewIsChat() {
        val firstList = CompletableDeferred<Unit>()
        app.core.firstTerminalListHolds["h1"] = firstList
        show(LastLocation("h1", terminal = "t1", view = AgentView.TERMINAL))
        val home = model.screen as Screen.Agents
        compose.runOnIdle {
            model.setDefaultAgentView(AgentView.CHAT)
            firstList.complete(Unit)
        }
        compose.waitUntil(5_000) { model.screen is Screen.Terminal }
        compose.runOnIdle {
            assertEquals("t1", (model.screen as Screen.Terminal).viewer.terminal.address)
            assertSame(home, (model.screen as Screen.Terminal).returnTo)
            assertEquals(AgentView.CHAT, model.agentView)
        }
        compose.runOnIdle { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertSame(home, model.screen) }
        compose.onNodeWithTag("tasks-home").assertIsDisplayed()
    }

    // D115: a first snapshot arriving after explicit Hosts navigation must not reopen the saved task.
    @Test fun visitingHostsCancelsDelayedColdTaskRestoration() {
        val firstList = CompletableDeferred<Unit>()
        app.core.firstTerminalListHolds["h1"] = firstList
        show(LastLocation("h1", terminal = "t1", view = AgentView.CHAT))
        val home = model.screen as Screen.Agents
        compose.onNodeWithTag("tasks-hosts").performClick()
        compose.runOnIdle { firstList.complete(Unit) }
        compose.waitUntil(5_000) { home.board.agents.any { it.terminal.address == "t1" && it.live } }
        compose.runOnIdle { assertEquals(Screen.Hosts, model.screen) }
        compose.onNodeWithText("me@home.test").assertIsDisplayed()
        compose.runOnIdle { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertSame(home, model.screen) }
        compose.onNodeWithTag("task-row:h1:t1").assertIsDisplayed()
    }

    // D113: rebuilding the Activity while Hosts is open must not rerun the startup home redirect.
    @Test fun hostsSurvivesActivityRecreationAndThemeChanges() {
        show()
        compose.onNodeWithTag("tasks-hosts").performClick()
        val retained = model
        activity!!.recreate()
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(retained, model)
            assertEquals(Screen.Hosts, model.screen)
        }
        compose.onNodeWithText("me@home.test").assertIsDisplayed()
        compose.runOnIdle { ThemeSettings.from(app).chooseMode(ThemeMode.DARK) }
        activity!!.recreate()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Screen.Hosts, model.screen) }
        compose.onNodeWithText("me@home.test").assertIsDisplayed()
    }
}
