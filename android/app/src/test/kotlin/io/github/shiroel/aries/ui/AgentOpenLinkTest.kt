package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.*
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            model = remember { HostsModel(core, scope, elapsedRealtime = { compose.mainClock.currentTime }) }
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

    // D32: selecting a locked Agent must authenticate again, with a usable retry after cancellation.
    @Test fun explicitlyOpeningALockedAgentRetriesAndOffersUnlockAfterCancellation() {
        val fake = core().apply { ensureKey(); setKeyProtection(KeyProtection.BIOMETRIC) }
        show(fake)
        compose.runOnIdle { model.openAgents() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(fake.connectAttempts.isEmpty())
            fake.failWith = CoreException.KeyLocked()
            model.openPushedAgent("h2", agent.address, openChat = true)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("h2"), fake.connectAttempts)
            assertTrue((model.prompt as Prompt.Problem).unlock != null)
            fake.failWith = null
        }
        compose.onNodeWithText("Unlock SSH key").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(AgentKey("h2", agent.address), model.currentAgentKey)
            assertEquals(listOf("h2", "h2"), fake.connectAttempts)
        }
    }

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
        // Verifying a fingerprint may take longer than the network opening budget.
        compose.mainClock.advanceTimeBy(21_000)
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

    // D81: an unreachable drawer target must expose cancellation and finish with an error, retaining the current detail.
    @Test fun unreachableAgentOpenIsVisibleCancellableAndBounded() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        compose.runOnIdle { model.openTerminal(agent) }
        compose.waitForIdle()
        val detail = model.screen
        core.failFor["h2"] = CoreException.Unreachable("offline")
        compose.runOnIdle { model.openPushedAgent("h2", agent.address) }
        compose.waitForIdle()
        compose.onNodeWithText("Opening box/t1…").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertSame(detail, model.screen) }
        compose.mainClock.advanceTimeBy(21_000)
        compose.runOnIdle { assertNull(model.prompt) }

        compose.runOnIdle { model.openPushedAgent("h2", agent.address) }
        compose.mainClock.advanceTimeBy(21_000)
        compose.runOnIdle {
            assertSame(detail, model.screen)
            assertTrue(model.prompt is Prompt.Problem)
        }
        compose.onNodeWithText("Opening box/t1…").assertDoesNotExist()
    }

    // D81: a cold notification must stop focusing after its deadline even if the retained Host watch later recovers.
    @Test fun coldNotificationOpenTimesOutWithoutLateNavigation() {
        val core = core().apply { failFor["h2"] = CoreException.Unreachable("offline") }
        show(core)
        compose.runOnIdle { model.openPushedAgent("h2", agent.address, openChat = true) }
        compose.waitForIdle()
        compose.onNodeWithText("Opening box/t1…").assertExists()
        compose.mainClock.advanceTimeBy(21_000)
        compose.runOnIdle {
            assertTrue(model.prompt is Prompt.Problem)
            model.dismiss()
            core.failFor.remove("h2")
            model.networkChanged()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Agents)
            assertTrue(core.chatsOpened.isEmpty())
        }
    }

    // D82: opening another Chat from Settings must retire the old Chat and make the new one own lifecycle events.
    @Test fun anotherAgentOpenedFromSettingsOwnsPauseAndReconnect() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        compose.runOnIdle { model.openPushedAgent("h1", agent.address, openChat = true) }
        compose.waitForIdle()
        val previous = (model.screen as Screen.Chat).chat
        compose.runOnIdle { model.openSettings(); model.openPushedAgent("h2", agent.address, openChat = true) }
        compose.waitForIdle()
        val current = (model.screen as Screen.Chat).chat
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(model.isViewedChat(previous))
            assertTrue(model.isViewedChat(current))
            assertEquals(1, core.chatReconnects)
            model.backgrounded(retainConnections = false)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(core.chatPaused)
            model.resumeScreens()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(core.chatPaused)
            model.closeChat()
            assertTrue(model.screen is Screen.Browse)
            assertEquals(LinkState.Closed, current.link?.state)
        }
    }

    // D82: restoring Settings' base route must not dismiss the inbox and cancel the caller currently opening.
    @Test fun openingACallerFromSettingsSurvivesTheBaseScreenChange() {
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
        compose.runOnIdle { model.openSettings() }
        compose.waitForIdle()
        compose.runOnIdle { model.openCallInbox() }
        compose.waitForIdle()
        compose.runOnIdle { model.openCallChat(caller()) }
        compose.waitForIdle()
        compose.runOnIdle { hold.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Chat)
            assertEquals(AgentKey("h2", agent.address), model.currentAgentKey)
        }
    }

    // D84: selecting a refreshing row from the tablet list must keep the current detail until the target resolves.
    @Test fun refreshingRowKeepsCurrentDetailUntilItsHostRecovers() {
        val core = core()
        show(core)
        compose.runOnIdle { model.openAgents() }
        compose.waitForIdle()
        val board = (model.screen as Screen.Agents).board
        compose.runOnIdle { model.openAgent(board, board.agents.first { it.host.id == "h1" }) }
        compose.waitForIdle()
        val detail = model.screen
        core.failFor["h2"] = CoreException.Unreachable("offline")
        compose.runOnIdle { board.links.getValue("h2").networkChanged() }
        compose.waitForIdle()
        compose.runOnIdle { model.selectListedAgent(board, board.agents.first { it.host.id == "h2" }) }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(detail, model.screen) }
        core.failFor.remove("h2")
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(AgentKey("h2", agent.address), model.currentAgentKey) }
    }

    // D41: Browse must borrow the home board's SSH line and watches, and release only its own view on Back.
    @Test fun browseReusesTheWarmHomeLineAndWatches() {
        val core = core()
        show(core)
        compose.runOnIdle { model.openAgents() }
        compose.waitForIdle()
        val home = model.screen
        val connection = core.connectionsOf.getValue("h1")
        val attempts = core.connectAttempts.size
        val watches = core.terminalWatchStarts["h1"]
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Browse)
            assertSame(connection, core.connectionsOf.getValue("h1"))
            assertEquals(attempts, core.connectAttempts.size)
            assertEquals(watches, core.terminalWatchStarts["h1"])
            core.push("h1", listOf(agent.copy(name = "Updated")))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Updated", (model.screen as Screen.Browse).browser.terminals.single().name)
            model.openAgents()
            assertSame(home, model.screen)
            assertFalse(connection.disconnected)
        }
    }

    // D39: an offline open must not expire while the phone is in the background.
    @Test fun agentOpeningBudgetPausesWhileBackgrounded() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        core.failFor["h2"] = CoreException.Unreachable("offline")
        compose.runOnIdle { model.openPushedAgent("h2", agent.address) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { model.backgrounded() }
        compose.mainClock.advanceTimeBy(60_000)
        compose.runOnIdle {
            assertNull(model.prompt)
            assertNotNull(model.agentOpening)
            model.resumeScreens()
        }
        compose.mainClock.advanceTimeBy(14_000)
        compose.runOnIdle { assertNull(model.prompt); assertNotNull(model.agentOpening) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertTrue(model.prompt is Prompt.Problem)
            assertNull(model.agentOpening)
        }
    }

    // D44: a superseded trust decision must neither remain visible nor revive a cancelled open.
    @Test fun supersedingAnOpenDismissesItsTrustDecisionAndIgnoresAQueuedTrust() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        core.requireTrust = "SHA256:new"
        compose.runOnIdle { model.openPushedAgent("h2", agent.address) }
        compose.waitForIdle()
        val oldPrompt = model.prompt as Prompt.TrustHostKey
        compose.runOnIdle { model.openDrawerAgent(AgentEntry(model.hosts[0], agent)) }
        compose.waitForIdle()
        val attempts = core.connectAttempts.size
        compose.runOnIdle {
            assertNull(model.prompt)
            model.trust(oldPrompt)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(AgentKey("h1", agent.address), model.currentAgentKey)
            assertEquals(attempts, core.connectAttempts.size)
        }
    }

    // D42: a failed Browse opened while home connects must not shadow that home's recovered live line.
    @Test fun agentOpenPrefersTheUpHomeLineOverAFailedBrowse() {
        val core = core()
        val hold = CompletableDeferred<Unit>()
        var attempts = 0
        val routed = object : AriesCoreInterface by core {
            override suspend fun connect(hostId: String): HostConnection {
                if (hostId == "h1") {
                    when (++attempts) {
                        1 -> hold.await()
                        2 -> Unit
                        else -> throw CoreException.AuthRejected()
                    }
                }
                return core.connect(hostId)
            }
        }
        show(routed)
        compose.runOnIdle { model.openAgents() }
        compose.waitForIdle()
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        compose.runOnIdle {
            core.connectionsOf.getValue("h1").dead = true
            model.foregrounded()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.screen is Screen.Browse)
            hold.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { model.openDrawerAgent(AgentEntry(model.hosts[0], agent)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(AgentKey("h1", agent.address), model.currentAgentKey)
            assertNull(model.prompt)
        }
    }

    // D43: Pin and Unpin must use a live Browse when the retained home line needs recovery.
    @Test fun pinUsesTheUpBrowseInsteadOfAFailedHomeLine() {
        val core = core().apply { failFor["h1"] = CoreException.AuthRejected() }
        show(core)
        compose.runOnIdle { model.openAgents() }
        compose.waitForIdle()
        core.failFor.remove("h1")
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        val entry = AgentEntry(model.hosts[0], agent)
        compose.runOnIdle { assertTrue(model.canPin(entry)); model.pin(entry, true) }
        compose.waitForIdle()
        compose.runOnIdle { model.pin(entry, false) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("h1 box/t1 pinned=true", "h1 box/t1 pinned=false"), core.labelWrites.filter { "pinned=" in it }) }
    }

    // D45: switching a live drawer Agent must not show a modal while its existing line answers.
    @Test fun liveDrawerSwitchDoesNotShowAnOpeningModalDuringTheRoundTrip() {
        val core = core()
        show(core)
        compose.runOnIdle { model.connect(model.hosts[0]) }
        compose.waitForIdle()
        val hold = CompletableDeferred<Unit>()
        core.terminalListHolds["h1"] = hold
        compose.runOnIdle { model.openDrawerAgent(AgentEntry(model.hosts[0], agent)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(model.agentOpening)
            hold.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(AgentKey("h1", agent.address), model.currentAgentKey) }
    }

}
