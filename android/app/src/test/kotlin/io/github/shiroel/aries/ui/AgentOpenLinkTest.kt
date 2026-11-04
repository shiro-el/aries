package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.*
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: detail navigation and live status through the core API. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentOpenLinkTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: HostsModel
    private val agent = TerminalInfo("box/t1", "Agent", "", listOf("claude"), "/repo", "live", AgentStatus.Working)

    private fun core() = FakeCore().apply {
        addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
        addHost("work", Endpoint.Ssh("work.test", 22u, "me"))
        terminalsOf["h1"] = listOf(agent)
        terminalsOf["h2"] = listOf(agent)
    }

    private fun show(core: AriesCoreInterface) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        System.getenv("ARIES_AGENT_OPEN_RENDER_DIR")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun caller() = CallEntry(model.hosts[1], CallInfo("h2", "c1", 100uL, agent.address, true,
        "Review", "Ready", CallUrgency.CHECK, emptyList(), null, true))

    // Regression: inbox-only Chat must follow its own Host/address, even when Browse watches the same address on another Host.
    @Test fun inboxChatFollowsItsHostStatusAcrossReconnectAndReleasesTheLineOnBack() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        val browse = model.screen
        compose.runOnIdle { model.openCallInbox(); model.openCallChat(caller()) }
        compose.waitForIdle()
        compose.runOnIdle { core.push("h2", listOf(agent.copy(agentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, "Which?")))) }
        compose.waitForIdle()
        screenshot("inbox-own-host-blocked")
        compose.runOnIdle {
            assertEquals(AgentStatus.Blocked(BlockedReason.QUESTION, "Which?"), (model.screen as Screen.Chat).chat.terminal.agentStatus)
            core.push("h1", listOf(agent.copy(agentStatus = AgentStatus.Error("other Host"))))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(AgentStatus.Blocked(BlockedReason.QUESTION, "Which?"), (model.screen as Screen.Chat).chat.terminal.agentStatus)
            core.terminalsOf["h2"] = listOf(agent)
            model.networkChanged()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(AgentStatus.Working, (model.screen as Screen.Chat).chat.terminal.agentStatus)
            model.closeChat()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(browse, model.screen)
            assertTrue(core.connectionsOf.getValue("h2").disconnected)
            assertFalse(core.connectionsOf.getValue("h1").disconnected)
        }
    }
    // Regression: Trust must resume the inbox's caller action instead of replacing Browse and leaving its old streams alive.
    @Test fun trustingACallerResumesItsChatAndPreservesTheOriginalBrowse() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        val browse = model.screen
        val home = core.connectionsOf.getValue("h1")
        core.requireTrust = "SHA256:new"
        compose.runOnIdle { model.openCallInbox(); model.openCallChat(caller()) }
        compose.waitForIdle()
        compose.runOnIdle { model.trust(model.prompt as Prompt.TrustHostKey) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(AgentKey("h2", "box/t1"), model.currentAgentKey)
            assertTrue(model.screen is Screen.Chat)
            assertFalse(model.callInboxOpen)
            assertFalse(home.disconnected)
            model.closeChat()
            assertSame(browse, model.screen)
        }
    }

    // Regression: late notification focus must not take over after the user chooses another navigation surface.
    @Test fun delayedNotificationFocusIsDiscardedWhenTheUserStartsSearching() {
        val hold = CompletableDeferred<Unit>()
        val core = core().apply { firstTerminalListHolds["h2"] = hold }
        show(core)
        compose.runOnIdle { model.openPushedAgent("h2", agent.address, openChat = true) }
        compose.waitForIdle()
        compose.runOnIdle { model.openQuickOpen(); model.closeQuickOpen(); hold.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Agents)
            assertTrue(core.chatsOpened.isEmpty())
            assertEquals(listOf("h2"), core.connectAttempts)
        }
    }

    // Regression: a pending other-Host open must stay cancelled after opening and closing another Agent on the same Browse instance.
    @Test fun navigatingAwayAndBackCancelsAnOutstandingOtherHostOpen() {
        val hold = CompletableDeferred<Unit>()
        val core = core()
        val delayed = object : AriesCoreInterface by core {
            override suspend fun connect(hostId: String): HostConnection {
                if (hostId == "h2") hold.await()
                return core.connect(hostId)
            }
        }
        show(delayed)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        val browse = model.screen
        compose.runOnIdle { model.openPushedAgent("h2", agent.address) }
        compose.waitForIdle()
        compose.runOnIdle { model.openTerminal(agent) }
        compose.waitForIdle()
        compose.runOnIdle { model.closeTerminal(); hold.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(browse, model.screen)
            assertFalse(core.connectionsOf.containsKey("h2"))
        }
    }

    // Regression: inbox entry from an all-Host board must not create a second SSH line for a Host already watched by the board.
    @Test fun inboxReusesTheBoardsConnectionAndKeepsItAliveOnBack() {
        val core = core()
        show(core)
        compose.runOnIdle { model.openAgents() }
        compose.waitForIdle()
        val board = model.screen
        val work = core.connectionsOf.getValue("h2")
        compose.runOnIdle { model.openCallInbox(); model.openCallChat(caller()) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(work, core.connectionsOf.getValue("h2"))
            assertFalse(work.disconnected)
            model.closeChat()
            assertSame(board, model.screen)
            assertFalse(work.disconnected)
        }
    }

    // Regression: another stored drawer Agent must open while notification focus waits, and closing it must not revive that focus.
    @Test fun openingAnotherDrawerAgentSupersedesDelayedNotificationFocus() {
        val hold = CompletableDeferred<Unit>()
        val core = core().apply { firstTerminalListHolds["h2"] = hold }
        show(core)
        compose.runOnIdle { model.openPushedAgent("h2", agent.address, openChat = true) }
        compose.waitForIdle()
        compose.runOnIdle { model.openDrawerAgent(AgentEntry(model.hosts[0], agent)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(AgentKey("h1", agent.address), model.currentAgentKey)
            model.closeTerminal()
            hold.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.screen is Screen.Agents) }
    }

    // Regression: replacing Browse directly must stop the previous Terminal stream and release its connection.
    @Test fun connectingAnotherHostReleasesThePreviousViewAndLine() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        compose.runOnIdle { model.openTerminal(agent) }
        compose.waitForIdle()
        val home = core.connectionsOf.getValue("h1")
        compose.runOnIdle { model.connect(model.hosts[1]) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(home.views.single().detached)
            assertTrue(home.disconnected)
            assertEquals("h2", (model.screen as Screen.Browse).browser.host.id)
        }
    }

    // Regression: confirming a jump Host key must resume the pending caller Chat, just like the final Host's trust flow.
    @Test fun trustingACallersJumpHostResumesItsChat() {
        val core = core().apply {
            setJumpHosts("h2", listOf(JumpHost("jump.test", 22u, "me", null, null)))
            requireJumpTrust = "SHA256:jump"
        }
        show(core)
        compose.runOnIdle { model.openCallInbox(); model.openCallChat(caller()) }
        compose.waitForIdle()
        compose.runOnIdle { model.trust(model.prompt as Prompt.TrustJumpHostKey) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(AgentKey("h2", agent.address), model.currentAgentKey)
            assertTrue(model.screen is Screen.Chat)
            assertFalse(model.callInboxOpen)
        }
    }

}
