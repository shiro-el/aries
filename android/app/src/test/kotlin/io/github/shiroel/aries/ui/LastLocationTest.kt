package io.github.shiroel.aries.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.PushTarget
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.SpaceInfo
import io.github.shiroel.aries.core.TabInfo
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asContextElement

/** Seam 3: navigation survives a new app model and reconnects through the core API. */
@RunWith(RobolectricTestRunner::class)
class LastLocationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val generation = mutableStateOf(0)
    private val pushTarget = mutableStateOf<PushTarget?>(null)
    private var incomingShare: Intent? = null
    private lateinit var model: HostsModel
    private val terminal = TerminalInfo("box/t1", "shell", "", listOf("sh"), "/repo", "live", null)

    private fun core() = FakeCore().apply {
        addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
        terminals = listOf(terminal)
        structure = HostStructure(listOf(SpaceInfo("s1", "repo", "/repo", listOf(
            TabInfo("tab1", "first", emptyList()),
            TabInfo("tab2", "second", listOf(PaneInfo("p1", terminal.address, terminal))),
        ))), emptyList(), null)
    }

    private fun show(core: AriesCoreInterface) {
        context.getSharedPreferences("last_location", Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent {
            key(generation.value) {
                val scope = rememberCoroutineScope()
                model = remember {
                    HostsModel(core, scope, keyWork = inKeyWork.asContextElement(true), locationStore = LastLocationStore.from(context))
                }
                LaunchedEffect(model) {
                    incomingShare?.let(model::receiveShare)
                    model.start(pushTarget.value)
                }
                AriesApp(model)
            }
        }
        compose.waitForIdle()
    }

    private fun restart(target: PushTarget? = null) {
        compose.runOnIdle {
            pushTarget.value = target
            generation.value++
        }
        compose.waitForIdle()
    }

    private fun openTerminal() {
        compose.runOnIdle { model.connect(model.hosts.first()) }
        compose.waitForIdle()
        compose.runOnIdle { model.openTerminal(terminal) }
        compose.waitForIdle()
    }

    @Test fun reopensTheLastHostSpaceAndSelectedTabAfterRestart() {
        show(core())
        compose.runOnIdle { model.connect(model.hosts.single()) }
        compose.waitForIdle()
        compose.runOnIdle {
            val browser = (model.screen as Screen.Browse).browser
            browser.openSpace("s1")
            browser.selectTab(browser.openSpace!!.tabs[1])
        }
        compose.waitForIdle()

        restart()

        compose.runOnIdle {
            val browser = (model.screen as Screen.Browse).browser
            assertEquals("h1", browser.host.id)
            assertEquals("s1", browser.openSpaceId)
            assertEquals("tab2", browser.tab?.id)
        }
    }

    @Test fun reattachesTheExactMachineTerminalAndBackReturnsToItsTab() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts.single()) }
        compose.waitForIdle()
        compose.runOnIdle {
            val browser = (model.screen as Screen.Browse).browser
            browser.openSpace("s1")
            browser.selectTab(browser.openSpace!!.tabs[1])
            model.openTerminal(terminal)
        }
        compose.waitForIdle()

        restart()

        compose.runOnIdle {
            assertEquals("box/t1", (model.screen as Screen.Terminal).viewer.terminal.address)
            assertEquals("box/t1", core.connection!!.views.single().address)
            model.closeTerminal()
            assertEquals("tab2", (model.screen as Screen.Browse).browser.tab?.id)
        }
    }

    @Test fun restoresChatEvenWhenTheDefaultAgentViewHasChanged() {
        val core = core().apply { terminals = listOf(terminal.copy(agentStatus = AgentStatus.Working)) }
        show(core)
        compose.runOnIdle { model.connect(model.hosts.single()) }
        compose.waitForIdle()
        compose.runOnIdle {
            model.openTerminal(core.terminals.single())
            model.switchView(AgentView.CHAT)
            model.setDefaultAgentView(AgentView.TERMINAL)
        }
        compose.waitForIdle()

        restart()

        compose.runOnIdle {
            assertEquals("box/t1", (model.screen as Screen.Chat).chat.terminal.address)
            assertEquals(AgentView.TERMINAL, model.agentView)
            model.closeChat()
            assertEquals("tab2", (model.screen as Screen.Browse).browser.tab?.id)
        }
    }

    @Test fun cancellingTheBiometricUnlockReturnsToHostsWithoutAnotherPrompt() {
        val core = core().apply {
            ensureKey()
            setKeyProtection(KeyProtection.BIOMETRIC)
        }
        show(core)
        openTerminal()
        core.failWith = CoreException.KeyLocked()

        restart()
        compose.runOnIdle { model.foregrounded() }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(Screen.Hosts, model.screen)
            assertNull(model.prompt)
            assertTrue("the first foreground refresh must not unlock the same Host again", core.snapshotsRequested.isEmpty())
            assertTrue("restoration uses the key-work context", core.offKeyWork.isEmpty())
        }
    }

    @Test fun aGoneTerminalFallsBackToItsTabEvenWhenTheLayoutStillContainsIt() {
        val core = core()
        show(core)
        openTerminal()
        core.terminals = emptyList()

        restart()

        compose.runOnIdle {
            assertEquals("tab2", (model.screen as Screen.Browse).browser.tab?.id)
            assertTrue(core.connection!!.views.isEmpty())
        }
    }

    @Test fun aGoneTabFallsBackToItsSpace() {
        val core = core()
        show(core)
        openTerminal()
        val space = core.structure!!.spaces.single()
        core.structure = HostStructure(listOf(space.copy(tabs = space.tabs.take(1))), emptyList(), null)
        core.terminals = emptyList()

        restart()

        compose.runOnIdle {
            val browser = (model.screen as Screen.Browse).browser
            assertEquals("s1", browser.openSpaceId)
            assertEquals("tab1", browser.tab?.id)
        }
    }

    @Test fun aGoneSpaceFallsBackToItsHost() {
        val core = core()
        show(core)
        openTerminal()
        core.structure = HostStructure(emptyList(), emptyList(), null)
        core.terminals = emptyList()

        restart()

        compose.runOnIdle {
            val browser = (model.screen as Screen.Browse).browser
            assertEquals("h1", browser.host.id)
            assertNull(browser.openSpaceId)
        }
    }

    @Test fun aRemovedHostFallsBackToTheHostList() {
        val core = core()
        show(core)
        openTerminal()
        core.removeHost("h1")

        restart()

        compose.runOnIdle {
            assertEquals(Screen.Hosts, model.screen)
            assertNull(model.prompt)
        }
    }

    @Test fun returningToTheHostListClearsTheSavedLocation() {
        show(core())
        openTerminal()
        compose.runOnIdle { model.back() }
        compose.waitForIdle()

        restart()

        compose.runOnIdle { assertEquals(Screen.Hosts, model.screen) }
    }

    private fun twoHosts() = core().apply {
        addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
        terminalsOf["h1"] = listOf(terminal)
        terminalsOf["h2"] = listOf(terminal.copy(address = "t1", name = "other"))
    }

    @Test fun aColdNotificationOpensItsTargetBeforeAnySavedTerminal() {
        val core = twoHosts()
        show(core)
        openTerminal()

        restart(PushTarget("h2", "t1"))

        compose.runOnIdle {
            val viewer = (model.screen as Screen.Terminal).viewer
            assertEquals("h2", viewer.link!!.host.id)
            assertEquals("t1", viewer.terminal.address)
            assertTrue(core.connectionsOf.getValue("h1").views.isEmpty())
        }
    }

    @Test fun aNotificationDuringRestorationWinsOverALateSavedHostConnection() {
        val core = twoHosts()
        var gate: CompletableDeferred<Unit>? = null
        val delayed = object : AriesCoreInterface by core {
            override suspend fun connect(hostId: String): HostConnection {
                if (hostId == "h1") gate?.await()
                return core.connect(hostId)
            }
        }
        show(delayed)
        openTerminal()
        gate = CompletableDeferred()
        restart()
        compose.runOnIdle { model.start(PushTarget("h2", "t1")) }
        compose.waitForIdle()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals("h2", (model.screen as Screen.Terminal).viewer.link!!.host.id)
            model.start()
            assertEquals("h2", (model.screen as Screen.Terminal).viewer.link!!.host.id)
        }
    }

    @Test fun aGoneTerminalOpenedFromANotificationKeepsItsLastKnownSpace() {
        val core = core()
        show(core)
        compose.runOnIdle { model.openPushedAgent("h1", "box/t1") }
        compose.waitForIdle()
        val space = core.structure!!.spaces.single()
        core.structure = HostStructure(listOf(space.copy(tabs = space.tabs.take(1))), emptyList(), null)
        core.terminals = emptyList()

        restart()

        compose.runOnIdle { assertEquals("s1", (model.screen as Screen.Browse).browser.openSpaceId) }
    }

    @Test fun anIncomingShareKeepsItsAgentPickerInsteadOfRestoringAnOldTerminal() {
        show(core())
        openTerminal()
        incomingShare = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "review this")

        restart()

        compose.runOnIdle {
            assertTrue(model.screen is Screen.Agents)
            assertEquals("review this", model.pendingShare?.text)
        }
    }
}
