package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.HostConnection
import kotlinx.coroutines.CompletableDeferred
import io.github.shiroel.aries.core.CallAnswer
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: inbox navigation, history and caller Chat, against the core API boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class CallHistoryTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun call(
        host: String,
        id: String,
        title: String,
        atMs: ULong,
        terminal: String? = "t3",
        topLevel: Boolean = true,
        urgency: CallUrgency = CallUrgency.CHECK,
        choices: List<String> = emptyList(),
        answer: CallAnswer? = null,
    ) = CallInfo(
        host, id, atMs, terminal, topLevel, title, "body of $title", urgency, choices, answer,
        terminal != null && topLevel && urgency != CallUrgency.RECORD,
    )

    private val agent = TerminalInfo("t3", "claude", "", listOf("claude"), "/repo", "live")

    private fun twoHosts() = FakeCore().apply {
        addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        addHost("server", Endpoint.Ssh("10.0.0.3", 22u, "me"))
        ensureKey()
    }

    private lateinit var model: HostsModel
    private lateinit var back: androidx.activity.OnBackPressedDispatcher

    private fun show(core: AriesCoreInterface) {
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.waitForIdle()
    }

    private fun openCalls() {
        compose.onNodeWithTag("console-inbox").performClick()
        compose.waitForIdle()
    }

    @Test
    fun missingBeaconLinksToThatHostsInstallInSettings() {
        val core = twoHosts().apply {
            terminalsOf["h2"] = (1..40).map { agent.copy(address = "t$it", name = "Agent $it") }
        }
        val installs = mutableListOf<String>()
        val api = object : AriesCoreInterface by core {
            override suspend fun installBeacon(hostId: String, fetcher: io.github.shiroel.aries.core.BeaconFetcher): io.github.shiroel.aries.core.BeaconInstall {
                installs += hostId
                return core.installBeacon(hostId, fetcher)
            }
        }
        show(api)
        compose.onNodeWithText("home").performClick()
        openCalls()
        compose.runOnIdle {
            core.callListeners.getValue("h1").onState(io.github.shiroel.aries.core.CallFeedState.Reconnecting("connection lost"))
        }
        compose.onNodeWithText(str(R.string.call_inbox_install_beacon, "home")).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.calls_feed_down, "h1", "connection lost")).assertExists()
        compose.runOnIdle {
            core.callListeners.getValue("h2").onState(io.github.shiroel.aries.core.CallFeedState.BeaconNotInstalled(
                "the Beacon is not installed on this Host",
            ))
        }
        compose.onNodeWithText(str(R.string.call_inbox_install_beacon, "server")).performClick()
        compose.onNodeWithTag("call-inbox-panel").assertDoesNotExist()
        compose.onNodeWithTag("host-actions:h2").assertExists()
        compose.onNodeWithText(str(R.string.beacon_install_button)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(str(R.string.beacon_version, "0.1.0")).assertExists()
        assertEquals(listOf("h2"), installs)
    }

    @Test
    fun installLinkFollowsTheTypedStateAndIgnoresUnrelatedOrEmptyFailures() {
        val core = twoHosts()
        show(core)
        openCalls()
        val install = str(R.string.call_inbox_install_beacon, "home")
        // Diagnostic wording is display data, not a discriminator.
        compose.runOnIdle {
            core.callListeners.getValue("h1").onState(
                io.github.shiroel.aries.core.CallFeedState.BeaconNotInstalled(""),
            )
        }
        compose.onNodeWithText(install).assertExists()
        for (detail in listOf("connection lost", "", "the Beacon is not installed on this Host")) {
            compose.runOnIdle {
                core.callListeners.getValue("h1").onState(
                    io.github.shiroel.aries.core.CallFeedState.Reconnecting(detail),
                )
            }
            compose.onNodeWithText(install).assertDoesNotExist()
            compose.onNodeWithText(str(R.string.calls_feed_down, "h1", detail)).assertExists()
        }
        compose.runOnIdle {
            core.callListeners.getValue("h1").onState(
                io.github.shiroel.aries.core.CallFeedState.BeaconNotInstalled("missing"),
            )
        }
        compose.onNodeWithText(install).assertExists()
        compose.runOnIdle {
            core.callListeners.getValue("h1").onState(io.github.shiroel.aries.core.CallFeedState.Live)
        }
        compose.onNodeWithText(install).assertDoesNotExist()
    }

    @Test
    fun helperChoicesStayInHistoryWithoutAnInboxCountOrHumanAnswerButtons() {
        val core = twoHosts().apply {
            callsOf["h1"] = listOf(call("h1", "child", "Helper report", 1uL, topLevel = false, choices = listOf("Continue")))
        }
        show(core)
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertDoesNotExist()
        openCalls()
        compose.onNodeWithText("Helper report").assertExists()
        compose.onNodeWithContentDescription(str(R.string.call_answer_choice_label, "Helper report", "Continue")).assertDoesNotExist()
    }

    @Test
    fun inboxUsesTheBrowsedAgentsNameAndTracksLiveNamesWithAddressFallback() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = listOf(agent.copy(name = "Review agent", agentStatus = io.github.shiroel.aries.core.AgentStatus.Working))
            terminalsOf["h2"] = listOf(agent.copy(name = "Other host agent", agentStatus = io.github.shiroel.aries.core.AgentStatus.Working))
            callsOf["h1"] = listOf(call("h1", "c1", "Question", 1uL))
            callsOf["h2"] = listOf(call("h2", "c2", "Other question", 2uL))
        }
        show(core)
        compose.onNodeWithText("home").performClick()
        openCalls()
        compose.onNodeWithText(str(R.string.call_inbox_caller, "Review agent", "home")).assertExists()
        // The same Terminal address on an unbrowsed Host must not borrow home's name.
        compose.onNodeWithText(str(R.string.call_inbox_caller, "t3", "server")).assertExists()
        core.push("h1", listOf(agent.copy(name = "Renamed agent", agentStatus = io.github.shiroel.aries.core.AgentStatus.Working)))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_inbox_caller, "Renamed agent", "home")).assertExists()
        core.push("h1", listOf(agent.copy(name = "", agentStatus = io.github.shiroel.aries.core.AgentStatus.Working)))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_inbox_caller, "t3", "home")).assertExists()
    }

    @Test
    fun inboxUsesTheAgentsSnapshotForEachHostsWholeTerminalAddress() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = listOf(agent.copy(name = "Review agent", agentStatus = io.github.shiroel.aries.core.AgentStatus.Working))
            terminalsOf["h2"] = listOf(agent.copy(name = "Remote agent", agentStatus = io.github.shiroel.aries.core.AgentStatus.Working))
            callsOf["h1"] = listOf(call("h1", "c1", "Question", 1uL))
            callsOf["h2"] = listOf(call("h2", "c2", "Other question", 2uL))
        }
        show(core)
        compose.openConsoleDrawer()
        compose.onNodeWithText(str(R.string.agents_button)).performClick()
        openCalls()
        compose.onNodeWithText(str(R.string.call_inbox_caller, "Review agent", "home")).assertExists()
        compose.onNodeWithText(str(R.string.call_inbox_caller, "Remote agent", "server")).assertExists()
    }

    @Test
    fun inboxCountsAnswerableCallsAndOpensOverTheCurrentHost() {
        val core = twoHosts().apply {
            callsOf["h1"] = listOf(
                call("h1", "c1", "Question", 1_000uL, choices = listOf("Yes", "No")),
                call("h1", "c2", "Record", 2_000uL, terminal = null, choices = listOf("Yes")),
            )
        }
        show(core)
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertTextEquals("1")
        openCalls()
        compose.onNodeWithTag("call-inbox-panel").assertExists()
        compose.onNodeWithText(str(R.string.terminals_title, "home")).assertExists()
        compose.onNodeWithTag("call-c1").assertExists()
        compose.onNodeWithTag("console-inbox").performClick()
        compose.onNodeWithTag("call-inbox-panel").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.terminals_title, "home")).assertExists()
    }

    @Test
    @Config(qualifiers = "h2000dp") // every row on screen at once
    fun historyShowsEveryHostsCallsNewestFirstWithWhatTheyAsk() {
        val core = twoHosts().apply {
            callsOf["h1"] = listOf(
                call("h1", "c2", "Helper question", 2_000uL, terminal = "t9", topLevel = false),
                call(
                    "h1", "c1", "Review ready", 1_000uL, urgency = CallUrgency.URGENT, choices = listOf("Merge", "Wait"),
                    answer = CallAnswer(0u, "Merge", CallAnswerState.DELIVERED, 1_500uL, null),
                ),
            )
            callsOf["h2"] = listOf(call("h2", "c3", "From cron", 3_000uL, terminal = null))
        }
        show(core)
        openCalls()

        compose.onNodeWithTag("call-inbox-panel").assertExists()
        compose.onNodeWithText("Review ready").assertExists()
        compose.onNodeWithText("body of Review ready").assertExists()
        compose.onNodeWithText(str(R.string.call_choices, "Merge · Wait")).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Merge")).assertExists()
        compose.onNodeWithText(str(R.string.call_from_helper)).assertExists()
        compose.onNodeWithText(str(R.string.call_unconfirmed)).assertExists()
        // No Terminal to open for a Call whose caller was not confirmed.
        compose.onNodeWithContentDescription(str(R.string.call_open_chat_label, "t3", "Review ready")).assertExists()
        // Newest first, across Hosts.
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y }
        assertTrue(top("call-c3") < top("call-c2"))
        assertTrue(top("call-c2") < top("call-c1"))
    }

    @Test
    fun aNewCallUpdatesTheInboxWithoutCoveringTheScreenWithABanner() {
        val core = twoHosts()
        show(core)
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertDoesNotExist()
        core.sendCall(call("h2", "c1", "Deploy failed", 5_000uL, choices = listOf("Retry")))
        compose.waitForIdle()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertTextEquals("1")
        compose.onNodeWithText("Deploy failed").assertDoesNotExist()
        openCalls()
        compose.onNodeWithText("Deploy failed").assertExists()
        compose.onNodeWithTag("call-inbox-scrim").performClick()
        compose.onNodeWithTag("call-inbox-panel").assertDoesNotExist()
    }

    @Test
    fun drawerAndInboxAreExclusiveAndBackDismissesThePanelBeforeLeavingTheHost() {
        val core = twoHosts()
        show(core)
        compose.onNodeWithText("home").performClick()
        openCalls()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("call-inbox-panel").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.terminals_title, "home")).assertExists()
        openCalls()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("call-inbox-panel").assertDoesNotExist()
        compose.onNodeWithTag("settings-open").performClick()
        openCalls()
        compose.onNodeWithTag("console-drawer").assertIsNotDisplayed()
        compose.onNodeWithTag("call-inbox-panel").assertExists()
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.onNodeWithTag("call-inbox-panel").assertDoesNotExist()
        compose.onNodeWithText("me@10.0.0.2:22").assertExists()
    }

    @Test
    fun helperAndRecordOnlyCallsAreRecordedWithoutABanner() {
        val core = twoHosts()
        show(core)
        core.sendCall(call("h1", "c1", "Helper asks", 1_000uL, topLevel = false))
        core.sendCall(call("h1", "c2", "Progress note", 2_000uL, urgency = CallUrgency.RECORD))
        compose.waitForIdle()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()

        openCalls()
        compose.onNodeWithText("Helper asks").assertExists()
        compose.onNodeWithText("Progress note").assertExists()
    }

    @Test
    fun theForegroundWatchNeverAsksForABiometricUnlockButTheInboxDoes() {
        val core = twoHosts().apply { setKeyProtection(KeyProtection.BIOMETRIC) }
        show(core)
        assertEquals(emptyList<String>(), core.callWatchesStarted)

        openCalls()
        assertEquals(listOf("h1", "h2"), core.callWatchesStarted.sorted())
    }

    @Test
    fun opensTheCallingAgentInChatAndBackReturnsToTheUnderlyingHost() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = listOf(agent)
            callsOf["h1"] = listOf(call("h1", "c1", "Review ready", 1_000uL))
        }
        show(core)
        compose.onNodeWithText("home").performClick()
        openCalls()
        compose.onNodeWithContentDescription(str(R.string.call_open_chat_label, "t3", "Review ready")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("h1" to "t3"), core.chatsOpened)
        compose.onNodeWithTag("call-inbox-panel").assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminals_title, "home")).assertExists()
        assertTrue(core.chatStopped)
        openCalls()
        compose.onNodeWithTag("call-c1").assertExists()
    }

    @Test
    fun waitingCallsComeBeforeNewerHistoryAndLongHistoryScrollsWithinThePanel() {
        val core = twoHosts().apply {
            callsOf["h1"] = listOf(call("h1", "question", "Waiting question", 1uL, choices = listOf("Yes"))) +
                (2..20).map { call("h1", "past-$it", "Record $it", it.toULong(), terminal = null) }
        }
        show(core)
        openCalls()
        val panel = compose.onNodeWithTag("call-inbox-panel").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("console-top-bar").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(panel.top >= bar.bottom)
        assertTrue(panel.height <= root.height * .7f)
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y }
        assertTrue(top("call-question") < top("call-past-20"))
        compose.onNodeWithTag("call-inbox-list").performScrollToNode(hasTestTag("call-past-2"))
        compose.onNodeWithText("Record 2").assertExists()
    }

    @Test
    fun openingACallerFromATerminalReleasesTheOldViewAndKeepsItsHostReturnTarget() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = listOf(agent)
            callsOf["h1"] = listOf(call("h1", "c1", "Review ready", 1_000uL))
        }
        show(core)
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        val view = core.connectionsOf.getValue("h1").views.single()
        // Agent headers adopt the shared inbox slot in ticket 05.
        compose.runOnIdle { model.openCallInbox() }
        compose.onNodeWithContentDescription(str(R.string.call_open_chat_label, "t3", "Review ready")).performClick()
        compose.waitForIdle()
        assertTrue(view.detached)
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.onNodeWithText(str(R.string.terminals_title, "home")).assertExists()
    }

    @Test
    fun closingAndReopeningTheInboxDiscardsALateChatConnection() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = listOf(agent)
            callsOf["h1"] = listOf(call("h1", "c1", "Review ready", 1_000uL))
        }
        val ready = CompletableDeferred<Unit>()
        val delayed = object : AriesCoreInterface by core {
            override suspend fun connect(hostId: String): HostConnection {
                ready.await()
                return core.connect(hostId)
            }
        }
        show(delayed)
        openCalls()
        compose.onNodeWithContentDescription(str(R.string.call_open_chat_label, "t3", "Review ready")).performClick()
        compose.onNodeWithTag("console-inbox").performClick()
        openCalls()
        compose.runOnIdle { ready.complete(Unit) }
        compose.onNodeWithTag("call-inbox-panel").assertExists()
        assertEquals(emptyList<Pair<String, String>>(), core.chatsOpened)
        assertTrue(core.connectionsOf.getValue("h1").disconnected)
    }

    @Test
    fun aCallWhoseTerminalIsGoneSaysSo() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = emptyList()
            callsOf["h1"] = listOf(call("h1", "c1", "Review ready", 1_000uL))
        }
        show(core)
        openCalls()
        compose.onNodeWithContentDescription(str(R.string.call_open_chat_label, "t3", "Review ready")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_terminal_gone_title)).assertExists()
        compose.onNodeWithText(str(R.string.call_terminal_gone_body, "t3", "home")).assertExists()
    }
}
