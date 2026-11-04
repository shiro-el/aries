package io.github.shiroel.aries.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Incoming share -> retained Chat -> upload/send, independent of the visible input panel. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ShareOwnershipTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // D02: compact Chat never mounts the catalog toolbar that previously owned incoming shares.
    @Test @Config(qualifiers = "h400dp")
    fun compactChatConsumesSharedFilesWithoutOpeningTools() = receiveIntoPanel(null)

    // D02: a retained live question replaces ThreadComposer before shared input is delivered.
    @Test fun threadQuestionPanelConsumesSharedFiles() = receiveIntoPanel("question")

    // D02: a Call without Choices has a composer but never mounts ChatCatalogControls.
    @Test fun threadCallPanelConsumesSharedFiles() = receiveIntoPanel("call")

    private fun receiveIntoPanel(panel: String?) {
        val terminal = TerminalInfo("t1", "Review", "", listOf("claude"), "/repo", "live", AgentStatus.Idle)
        val core = FakeCore().apply {
            terminals = listOf(terminal)
            addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
            ensureKey()
            if (panel == "call") callsOf["h1"] = listOf(CallInfo("h1", "review", 1000uL, "t1", true,
                "Review requested", "Inspect this", CallUrgency.CHECK, emptyList(), null, true))
        }
        val appearance = ThemeSettings(context.getSharedPreferences("share-owner", Context.MODE_PRIVATE)).apply {
            chooseLayout(if (panel == null) AppLayout.CONSOLE else AppLayout.THREAD)
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, appearance)
        }
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.waitForIdle()
        val question = ChatEntry("question", null, ChatEntryBody.Question("AskUserQuestion", "Which approach?", listOf(
            ChatQuestionPart("Which approach?", null, false, listOf(ChatQuestionOption("Safe", null))),
        ), ChatToolStatus.RUNNING, null))
        core.pushChat(ChatSnapshot(if (panel == "question") listOf(question) else emptyList(), ChatStatus.LIVE, null))
        if (panel == "question") core.push(listOf(terminal.copy(agentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, null))))
        compose.waitForIdle()
        val chat = (model.screen as Screen.Chat).chat
        val uri = Uri.parse("content://shared/review")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(2, 6, 1)))
        compose.runOnIdle { model.receiveShare(Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)) }
        compose.onNodeWithTag(if (panel == null) "agent-row:h1:t1" else "thread-agent:h1:t1").performClick()
        core.pushChat(ChatSnapshot(if (panel == "question") listOf(question) else emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        if (panel != null) compose.onNodeWithTag("thread-${if (panel == "question") "answer" else "call"}-panel").assertExists()
        compose.waitUntil(5_000) { chat.attachments.isNotEmpty() }
        assertEquals(1, core.connection!!.uploads.size)
        assertArrayEquals(byteArrayOf(2, 6, 1), core.connection!!.uploads.single().third)
        assertNull(chat.pendingShare)
        assertTrue(chat.canSend)
        assertTrue(core.connection!!.typed.isEmpty())
        // A question owns its own answer controls; its attachment stays ready for the next message.
        if (panel == "question") {
            core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
            core.push(listOf(terminal))
        }
        val send = context.getString(R.string.chat_send_button)
        if (panel == null) compose.onNodeWithText(send).assertIsEnabled().performClick()
        else compose.onNodeWithContentDescription(send).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, core.connection!!.typed.size)
    }
}
