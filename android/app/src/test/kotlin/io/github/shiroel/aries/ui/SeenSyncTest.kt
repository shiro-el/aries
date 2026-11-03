package io.github.shiroel.aries.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: visible Agent attention through the real screen lifecycle and fake core API. */
@RunWith(RobolectricTestRunner::class)
class SeenSyncTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var model: HostsModel
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private fun agent(turn: ULong, status: AgentStatus = AgentStatus.Done) = TerminalInfo(
        "box/t1", "claude", "", listOf("claude"), "/repo", "live", status, false, turn,
    )

    private fun open(
        view: AgentView = AgentView.TERMINAL,
        fromBrowse: Boolean = false,
        state: Lifecycle.State = Lifecycle.State.RESUMED,
    ): FakeCore {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("localhost", 22u, "me"))
            terminalsOf["h1"] = listOf(agent(1uL))
            setAgentView(view)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { AriesApp(model) }
        }
        compose.runOnIdle {
            owner.lifecycle.currentState = state
            if (fromBrowse) model.connect(core.hosts().single()) else model.openAgents()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            if (fromBrowse) model.openTerminal(agent(1uL))
            else {
                val board = (model.screen as Screen.Agents).board
                model.openAgent(board, board.agents.single())
            }
        }
        compose.waitForIdle()
        return core
    }

    @Test
    fun terminalSeesEachNewTurnOnceWithoutOverrulingMarkUnread() {
        val core = open()
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        // Desktop marks the current turn unread; unrelated updates must not undo that.
        core.push("h1", listOf(agent(1uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        core.push("h1", listOf(agent(2uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)

        // A stale snapshot never regresses the viewing session's turn.
        core.push("h1", listOf(agent(1uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)
    }

    @Test
    fun chatAcknowledgesNewTurnsOnlyWhileResumed() {
        val core = open(AgentView.CHAT)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        core.push("h1", listOf(agent(2uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)

        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        core.push("h1", listOf(agent(3uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)
    }

    @Test
    fun leavingTheAgentStopsAcknowledgementAndLookingAgainStartsANewSession() {
        val core = open()
        compose.runOnIdle { model.closeTerminal() }
        core.push("h1", listOf(agent(1uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        compose.runOnIdle {
            val board = (model.screen as Screen.Agents).board
            model.openAgent(board, board.agents.single())
        }
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=1"), core.labelWrites)
    }

    @Test
    fun switchingBetweenTerminalAndChatKeepsTheSameViewingSession() {
        val core = open()
        core.push("h1", listOf(agent(1uL)))
        compose.waitForIdle()
        compose.runOnIdle { model.switchView(AgentView.CHAT) }
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        core.push("h1", listOf(agent(2uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)
    }

    @Test
    fun anInboxCoveringChatPausesAcknowledgement() {
        val core = open(AgentView.CHAT)
        compose.runOnIdle { model.openCallInbox() }
        compose.waitForIdle()
        core.push("h1", listOf(agent(2uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        compose.runOnIdle { model.closeCallInbox() }
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)
    }

    @Test
    fun browseAlsoAcknowledgesNewTurnsAndWaitsForForegroundOnEntry() {
        val core = open(fromBrowse = true, state = Lifecycle.State.STARTED)
        assertEquals(emptyList<String>(), core.labelWrites)

        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        core.push("h1", listOf(agent(2uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)
    }

    @Test
    fun reconnectingKeepsUnreadUntilANewTurnIsViewed() {
        val core = open()
        core.push("h1", listOf(agent(1uL)))
        compose.waitForIdle()
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        core.push("h1", listOf(agent(2uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)
    }

    @Test
    fun aCallerOpenedFromHostsFollowsTurnsWithoutAnAgentListWatch() {
        val core = open(AgentView.CHAT)
        compose.runOnIdle { model.back() }
        compose.waitForIdle()
        core.labelWrites.clear()
        compose.runOnIdle { model.openCallInbox() }
        compose.waitForIdle()
        val call = CallInfo("h1", "call-1", 1_000uL, "box/t1", true, "Review", "Ready",
            CallUrgency.CHECK, emptyList(), null, true)
        compose.runOnIdle { model.openCallChat(CallEntry(core.hosts().single(), call)) }
        compose.waitForIdle()
        assertEquals(Screen.Hosts, (model.screen as Screen.Chat).returnTo)
        assertEquals(listOf("h1 box/t1 seen=1"), core.labelWrites)

        core.push("h1", listOf(agent(2uL)))
        compose.waitForIdle()
        assertEquals(listOf("h1 box/t1 seen=1", "h1 box/t1 seen=2"), core.labelWrites)
    }
}
