package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDeliveryStatusTest {
    @get:Rule val compose = createComposeRule()
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle)

    private fun open(core: FakeCore, live: Boolean = true): FakeConnection {
        core.terminals = listOf(agent)
        core.savedView = AgentView.CHAT
        core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val hosts = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(hosts) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        if (live) core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        return core.connection!!
    }

    private fun send(text: String) {
        compose.onNodeWithContentDescription("Message to the Agent").performTextInput(text)
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
    }

    @Test fun deliveryProgressBelongsToTheMessageAndTranscriptConfirmationReplacesItOnce() {
        val core = FakeCore()
        val connection = open(core)
        connection.sendReply = CompletableDeferred()
        send("review this change")
        compose.onNodeWithText("review this change").assertIsDisplayed()
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        screenshot("sending")
        connection.sendReply!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Entered in Terminal").assertIsDisplayed()
        screenshot("entered")
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User("review this change"))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Received by Agent").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("review this change").fetchSemanticsNodes().size == 1 }
        assertEquals(listOf("t1" to "review this change"), connection.typed)
        screenshot("received")
    }

    @Test fun aLostReplyOffersExplicitRetryAndRestoreWithoutAutomaticResending() {
        val core = FakeCore()
        val connection = open(core)
        connection.sendFailWith = CoreException.Closed()
        send("try this instruction")
        compose.onNodeWithText("Delivery unconfirmed · check the conversation before resending").assertExists()
        compose.onNodeWithText("Retry").assertExists()
        screenshot("failed")
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Resend message?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, connection.calls.count { it == "sendMessage" })
        compose.onNodeWithText("Restore to input").performClick()
        compose.onNodeWithContentDescription("Message to the Agent").assertTextEquals("try this instruction")
        connection.sendFailWith = null
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1" to "try this instruction"), connection.typed)
    }

    @Test fun initialLoadingExpiresToRecoveryAndAnEmptyLiveChatHasItsOwnState() {
        val core = FakeCore()
        open(core, live = false)
        compose.onNodeWithText("Loading conversation…").assertIsDisplayed()
        compose.onNodeWithText("No messages yet.").assertDoesNotExist()
        screenshot("initial-loading")
        compose.mainClock.advanceTimeBy(21_000)
        compose.onNodeWithText("Loading is taking longer than expected. Check the Host connection and try again.").assertExists()
        screenshot("loading-timeout")
        compose.onNodeWithText("Try again").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No messages yet.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Loading conversation…").assertDoesNotExist()
        screenshot("empty")
    }

    private fun screenshot(name: String) {
        System.getenv("ARIES_STATUS_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
