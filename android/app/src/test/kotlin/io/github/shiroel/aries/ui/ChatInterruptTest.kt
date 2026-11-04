package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
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
class ChatInterruptTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)

    private fun screenshot(name: String, dialog: Boolean = false) {
        System.getenv("ARIES_INTERRUPT_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
                root.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun open(core: FakeCore): FakeConnection {
        core.terminals = listOf(agent)
        core.savedView = AgentView.CHAT
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        return core.connection!!
    }

    @Test
    fun interruptionRequiresConfirmationAndPreservesTheDraftWhileSuppressingDuplicates() {
        val conn = open(FakeCore())
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("keep my draft")
        screenshot("chat-working")
        compose.onNodeWithText(str(R.string.chat_interrupt_button)).performClick()
        screenshot("interrupt-confirm", dialog = true)
        assertEquals(emptyList<String>(), conn.interrupted)
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        assertEquals(emptyList<String>(), conn.interrupted)

        conn.interruptHold = CompletableDeferred()
        compose.onNodeWithText(str(R.string.chat_interrupt_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1"), conn.interrupted)
        compose.onNodeWithText(str(R.string.chat_interrupt_pending)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("keep my draft")
        conn.interruptHold!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertExists()
        assertEquals(emptyList<Pair<String, String>>(), conn.typed)
    }

    @Test
    fun aStatusChangeWhileConfirmingSendsNothingAndFailureCanBeRetriedExplicitly() {
        val core = FakeCore()
        val conn = open(core)
        compose.onNodeWithText(str(R.string.chat_interrupt_button)).performClick()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Blocked(BlockedReason.PERMISSION, "Allow?"))))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).assertDoesNotExist()
        assertEquals(emptyList<String>(), conn.interrupted)

        core.push(listOf(agent))
        compose.waitForIdle()
        conn.interruptFailWith = CoreException.Closed()
        compose.onNodeWithText(str(R.string.chat_interrupt_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_failed, ""), substring = true).assertExists()
        assertEquals(listOf("t1"), conn.interrupted)
        conn.interruptFailWith = null
        conn.interruptOutcome = InterruptOutcome.STILL_WORKING
        compose.onNodeWithText(str(R.string.chat_interrupt_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_still_working)).assertExists()
        assertEquals(listOf("t1", "t1"), conn.interrupted)
    }
}
