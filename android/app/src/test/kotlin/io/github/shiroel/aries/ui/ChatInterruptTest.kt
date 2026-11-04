package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
    private lateinit var model: HostsModel
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
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        return core.connection!!
    }

    @Test
    fun workingComposerKeepsStopAvailableAlongsideDraftSubmission() {
        val conn = open(FakeCore())
        val input = compose.onNodeWithContentDescription(str(R.string.chat_input_label))
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.chat_send_button)).assertDoesNotExist()
        screenshot("composer-empty-stop")

        input.performTextInput(" ")
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.chat_send_button)).assertIsNotEnabled()
        input.performTextReplacement("x")
        screenshot("composer-typed-send")
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), conn.typed)
        compose.onNodeWithText("x").assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_queue_cancel_button)).assertExists()
        assertEquals(emptyList<String>(), conn.interrupted)

        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1"), conn.interrupted)
        assertEquals(emptyList<Pair<String, String>>(), conn.typed)
    }

    @Test
    fun anAttachmentWithoutTextQueuesTheAttachmentInsteadOfInterrupting() {
        val core = FakeCore()
        val conn = open(core)
        val file = File(context.cacheDir, "interrupt-notes.txt").apply { writeText("Review notes") }
        try {
            compose.runOnIdle {
                (model.screen as Screen.Chat).chat.attach(context, SharedContent(listOf(android.net.Uri.fromFile(file))))
            }
            compose.waitUntil(5_000) { conn.uploads.isNotEmpty() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(str(R.string.chat_input_label))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertIsEnabled()
            screenshot("composer-attachment-send")
            compose.onNodeWithText(str(R.string.chat_send_button)).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(emptyList<Pair<String, String>>(), conn.typed)
            compose.onNodeWithContentDescription(str(R.string.chat_queue_cancel_button)).assertExists()
            assertEquals(emptyList<String>(), conn.interrupted)
            core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
            core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
            compose.waitForIdle()
            assertEquals(listOf("t1" to "'/host/it'\"'\"'s file.jpg'"), conn.typed)
        } finally { file.delete() }
    }

    @Test
    fun interruptionRequiresConfirmationAndPreservesTheDraftWhileSuppressingDuplicates() {
        val conn = open(FakeCore())
        screenshot("chat-working")
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        screenshot("interrupt-confirm", dialog = true)
        assertEquals(emptyList<String>(), conn.interrupted)
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        assertEquals(emptyList<String>(), conn.interrupted)

        conn.interruptHold = CompletableDeferred()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1"), conn.interrupted)
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_pending)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("keep my draft")
        compose.onNodeWithText(str(R.string.chat_send_button)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("keep my draft")
        conn.interruptHold!!.complete(Unit)
        compose.waitForIdle()
        // Editing starts the next instruction; the preceding interrupt notice stays dismissed.
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertDoesNotExist()
        assertEquals(emptyList<Pair<String, String>>(), conn.typed)
    }

    @Test
    fun aStatusChangeWhileConfirmingSendsNothingAndFailureCanBeRetriedExplicitly() {
        val core = FakeCore()
        val conn = open(core)
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Blocked(BlockedReason.PERMISSION, "Allow?"))))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).assertDoesNotExist()
        assertEquals(emptyList<String>(), conn.interrupted)

        core.push(listOf(agent))
        compose.waitForIdle()
        conn.interruptFailWith = CoreException.Closed()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_failed), substring = true).assertExists()
        assertEquals(listOf("t1"), conn.interrupted)
        conn.interruptFailWith = null
        conn.interruptOutcome = InterruptOutcome.STILL_WORKING
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_still_working)).assertExists()
        assertEquals(listOf("t1", "t1"), conn.interrupted)

        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        core.push(emptyList())
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).assertDoesNotExist()
        assertEquals(listOf("t1", "t1"), conn.interrupted)
    }
    @Test
    fun editingDismissesInterruptButLinkAndStatusChangesPreserveOperationNotices() {
        val core = FakeCore()
        val conn = open(core)
        val chat = compose.runOnIdle { (model.screen as Screen.Chat).chat }
        compose.runOnIdle { chat.interrupt() }
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("next instruction")
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertDoesNotExist()
        compose.runOnIdle { chat.interrupt() }
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertExists()
        compose.runOnIdle {
            chat.attachmentFailed(java.io.IOException())
            chat.reconnect()
        }
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertExists()
        compose.onNodeWithText(str(R.string.attachments_read_failed)).assertExists()
        conn.interruptHold = CompletableDeferred()
        compose.runOnIdle { chat.interrupt() }
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        conn.interruptHold!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertExists()
        compose.onNodeWithText(str(R.string.attachments_read_failed)).assertExists()
        compose.runOnIdle { chat.link!!.lost(conn) }
        compose.waitForIdle()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.RECONNECTING, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertExists()
        compose.onNodeWithText(str(R.string.attachments_read_failed)).assertExists()
    }

    // D21 also covers automatic draft restoration when the Agent ends.
    @Test fun returningQueuedTextWhenTheAgentEndsKeepsAttachmentFailure() {
        val core = FakeCore()
        open(core)
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        val chat = compose.runOnIdle { (model.screen as Screen.Chat).chat }
        compose.runOnIdle {
            chat.draft = "queued instruction"
            chat.send()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            chat.attachmentFailed(java.io.IOException())
        }
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.NO_AGENT, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("queued instruction")
        compose.onNodeWithText(str(R.string.attachments_read_failed)).assertExists()
    }

    @Test fun delayedInterruptFailureSurvivesLinkAndStatusChanges() {
        val core = FakeCore()
        val conn = open(core)
        val chat = compose.runOnIdle { (model.screen as Screen.Chat).chat }
        conn.interruptHold = CompletableDeferred()
        conn.interruptFailWith = CoreException.Closed()
        compose.runOnIdle { chat.interrupt() }
        compose.runOnIdle { chat.link!!.lost(conn) }
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.RECONNECTING, null))
        compose.waitForIdle()
        conn.interruptHold!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_interrupt_failed), substring = true).assertExists()
    }

    // Catches losing focus when the pending Interrupt button disappears before the RPC replies.
    @Test
    fun completedInterruptKeepsItsFocusedResultAcrossLateStatusChanges() {
        val core = FakeCore()
        val conn = open(core)
        conn.interruptHold = CompletableDeferred()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        conn.interruptHold!!.complete(Unit)
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertIsFocused()
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite),
        )
        screenshot("interrupt-result-focused")
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertIsFocused()
    }

    @Test
    fun statusChangingBeforeTheInterruptReplyStillShowsAndFocusesTheResult() {
        val core = FakeCore()
        val conn = open(core)
        conn.interruptHold = CompletableDeferred()
        compose.onNodeWithContentDescription(str(R.string.chat_interrupt_confirm_button)).performClick()
        compose.onNodeWithText(str(R.string.chat_interrupt_confirm_button)).performClick()
        core.push(listOf(agent.copy(agentStatus = AgentStatus.Idle)))
        compose.waitForIdle()
        conn.interruptHold!!.complete(Unit)
        compose.onNodeWithText(str(R.string.chat_interrupt_stopped)).assertIsFocused()
    }

}
