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
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Seam 3: incoming Android share -> existing Agent list -> draft, against the fake core. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareAttachmentTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = context.getString(id)

    @Test fun sharedFilesAndTextWaitForAnAgentThenUploadIntoItsDraftWithoutSending() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            addHost("work", Endpoint.Ssh("10.0.0.3", 22u, "me"))
            terminalsOf["h1"] = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle, false, 0uL))
            terminalsOf["h2"] = listOf(TerminalInfo("box/t1", "codex", "", listOf("codex"), "/repo", "live", AgentStatus.Idle, false, 0uL))
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        val uris = arrayListOf(Uri.parse("content://shared/a"), Uri.parse("content://shared/b"))
        shadowOf(context.contentResolver).registerInputStream(uris[0], ByteArrayInputStream(byteArrayOf(1, 2)))
        shadowOf(context.contentResolver).registerInputStream(uris[1], ByteArrayInputStream(byteArrayOf(3)))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).putExtra(Intent.EXTRA_TEXT, "please inspect")
        compose.runOnIdle { model.receiveShare(intent) }
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Agents)
        assertTrue(core.chatsOpened.isEmpty())
        compose.onNodeWithText(str(R.string.attachments_choose_agent)).assertExists()
        val connection = core.connectionsOf.getValue("h2")
        val hold = CompletableDeferred<Unit>()
        connection.uploadHold = hold
        compose.onNodeWithTag("agent-row:h2:box/t1").performClick()
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Chat) // shared input opens Chat even if Terminal is the saved view
        compose.onNodeWithText(str(R.string.attachments_uploading)).assertExists()
        compose.onNodeWithText(str(R.string.chat_send_button)).assertIsNotEnabled()
        hold.complete(Unit)
        compose.waitUntil(5_000) { !(model.screen as Screen.Chat).chat.uploading }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("please inspect")
        assertEquals(listOf("box/t1", "box/t1"), connection.uploads.map { it.first })
        assertArrayEquals(byteArrayOf(1, 2), connection.uploads[0].third)
        assertArrayEquals(byteArrayOf(3), connection.uploads[1].third)
        assertTrue(connection.typed.isEmpty())
        assertTrue(core.connectionsOf.getValue("h1").uploads.isEmpty())
        captureAttachmentScreenshot(compose, "files-draft")
        connection.sendFailWith = CoreException.Closed()
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
        assertTrue(connection.typed.isEmpty())
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("please inspect")
        val remove = context.getString(R.string.attachments_remove_button, "attachment")
        compose.onAllNodesWithContentDescription(remove).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(remove)[0].performClick()
        compose.onAllNodesWithContentDescription(remove).assertCountEquals(1)
        connection.sendFailWith = null
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("box/t1" to "please inspect '/host/it'\"'\"'s file.jpg'"), connection.typed)
        compose.onAllNodesWithContentDescription(remove).assertCountEquals(0)
    }
}
