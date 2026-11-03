package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
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

/** Seam 3: Call history, the new-Call banner and opening a Call's Terminal, against a fake core. */
@RunWith(RobolectricTestRunner::class)
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

    private fun show(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.waitForIdle()
    }

    private fun openCalls() {
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasText(str(R.string.calls_button)))
        compose.onNodeWithText(str(R.string.calls_button)).performClick()
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp") // every row on screen at once
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

        compose.onNodeWithText(str(R.string.calls_title)).assertExists()
        compose.onNodeWithText("Review ready").assertExists()
        compose.onNodeWithText("body of Review ready").assertExists()
        compose.onNodeWithText(str(R.string.call_choices, "Merge · Wait")).assertExists()
        compose.onNodeWithText(str(R.string.call_answer_delivered, "Merge")).assertExists()
        compose.onNodeWithText(str(R.string.call_from_helper)).assertExists()
        compose.onNodeWithText(str(R.string.call_unconfirmed)).assertExists()
        // No Terminal to open for a Call whose caller was not confirmed.
        compose.onNodeWithContentDescription(str(R.string.call_open_terminal_label, "t3", "Review ready")).assertExists()
        // Newest first, across Hosts.
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y }
        assertTrue(top("call-c3") < top("call-c2"))
        assertTrue(top("call-c2") < top("call-c1"))
    }

    @Test
    fun aNewCallWhileTheAppIsOpenShowsABannerThatOpensHistory() {
        val core = twoHosts()
        show(core)
        compose.onNodeWithTag("call_banner").assertDoesNotExist()

        core.sendCall(call("h2", "c1", "Deploy failed", 5_000uL, urgency = CallUrgency.URGENT))
        compose.waitForIdle()
        compose.onNodeWithTag("call_banner").assertExists()
        compose.onNodeWithText(str(R.string.call_banner_from, "server", str(R.string.call_urgency_urgent))).assertExists()
        compose.onNodeWithText("Deploy failed").assertExists()

        compose.onNodeWithText(str(R.string.call_banner_view)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.calls_title)).assertExists()
        compose.onNodeWithTag("call-c1").assertExists()
    }

    @Test
    fun aBannerCanBeDismissed() {
        val core = twoHosts()
        show(core)
        core.sendCall(call("h1", "c1", "Look", 5_000uL))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_banner_dismiss)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
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
    fun theBannerWatchNeverAsksForABiometricUnlockButHistoryDoes() {
        val core = twoHosts().apply { setKeyProtection(KeyProtection.BIOMETRIC) }
        show(core)
        assertEquals(emptyList<String>(), core.callWatchesStarted)

        openCalls()
        assertEquals(listOf("h1", "h2"), core.callWatchesStarted.sorted())
    }

    @Test
    fun opensTheTerminalThatSentACallAndComesBackToHistory() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = listOf(agent)
            callsOf["h1"] = listOf(call("h1", "c1", "Review ready", 1_000uL))
        }
        show(core)
        openCalls()
        compose.onNodeWithContentDescription(str(R.string.call_open_terminal_label, "t3", "Review ready")).performClick()
        compose.waitForIdle()

        val view = core.connectionsOf.getValue("h1").views.single()
        assertEquals("t3", view.address)
        assertTrue(view.options.observe)

        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.calls_title)).assertExists()
        assertTrue(view.detached)
    }

    @Test
    fun aCallWhoseTerminalIsGoneSaysSo() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = emptyList()
            callsOf["h1"] = listOf(call("h1", "c1", "Review ready", 1_000uL))
        }
        show(core)
        openCalls()
        compose.onNodeWithContentDescription(str(R.string.call_open_terminal_label, "t3", "Review ready")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.call_terminal_gone_title)).assertExists()
        compose.onNodeWithText(str(R.string.call_terminal_gone_body, "t3", "home")).assertExists()
    }
}
