package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class RemoteFilesTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    @Test fun browsesFoldersPagesAndReturnsALineForTheDraft() {
        val requests = mutableListOf<Triple<String, ULong, UInt>>()
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?): RemoteDirectory {
                assertEquals("box/t7", address)
                return if (path == null) RemoteDirectory("/repo", listOf(RemoteFileEntry("src", true)))
                else RemoteDirectory("/repo/src", listOf(RemoteFileEntry("a.kt", false)))
            }
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                requests += Triple(path, offset, limit)
                return if (offset == 1uL) RemoteFilePage("first\nsecond\n", 1u, 2u, 3u)
                else RemoteFilePage("last", 3u, 3u, 3u)
            }
        }
        var draft = ""
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "box/t7", {}, { draft = it }) } }
        compose.onNodeWithText("src/").performClick()
        compose.onNodeWithText("a.kt").performClick()
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText(str(R.string.files_next_button)).performClick()
        compose.onNodeWithText("last").assertExists()
        compose.onNodeWithTag("files-line:3").performClick()
        assertEquals("'/repo/src/a.kt':3\nlast", draft)
        assertEquals(listOf(Triple("/repo/src/a.kt", 1uL, 200u), Triple("/repo/src/a.kt", 3uL, 200u)), requests)
    }
    @Test fun nonTextAndNulBytesHaveNoPreviewButFileCanBeReferenced() {
        var binary = false
        val conn = object : HostConnection(NoHandle) {
            override suspend fun listFiles(address: String, path: String?) =
                RemoteDirectory("/repo", listOf(RemoteFileEntry("binary", false)))
            override suspend fun readFilePage(address: String, path: String, offset: ULong, limit: UInt): RemoteFilePage {
                if (!binary) throw CoreException.Rpc("not_text", "not UTF-8")
                return RemoteFilePage("abc\u0000def", 1u, 1u, 1u)
            }
        }
        var draft = ""
        compose.setContent { MaterialTheme { RemoteFilesScreen(conn, "t1", {}, { draft = it }) } }
        compose.onNodeWithText("binary").performClick()
        compose.onNodeWithText(str(R.string.files_not_text)).assertExists()
        compose.onNodeWithText(str(R.string.files_back_folder_button)).performClick()
        binary = true
        compose.onNodeWithText("binary").performClick()
        compose.onNodeWithText(str(R.string.files_not_text)).assertExists()
        compose.onNodeWithText(str(R.string.files_send_file_button)).performClick()
        assertEquals("'/repo/binary'", draft)
    }

    @Test fun sendingAFileAppendsToTheExistingChatDraftWithoutSending() {
        val core = FakeCore().apply {
            terminals = listOf(TerminalInfo("t1", "agent", "", listOf("codex"), "/repo", "live", AgentStatus.Idle))
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("example.invalid", 22u, "me"))
        }
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val model = androidx.compose.runtime.remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("agent").performClick()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("Please review")
        core.connection!!.fileDirectory = RemoteDirectory("/repo", listOf(RemoteFileEntry("a'b.kt", false)))
        compose.onNodeWithTag("files-open").performClick()
        compose.onNodeWithText("a'b.kt").performClick()
        compose.onNodeWithText(str(R.string.files_send_file_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("Please review\n'/repo/a'\\''b.kt'")
        assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)

        // The same action from Terminal opens Chat with a reviewable draft.
        compose.onNodeWithText(str(R.string.agent_view_terminal)).performClick()
        compose.onNodeWithTag("files-open").performClick()
        compose.onNodeWithText("a'b.kt").performClick()
        compose.onNodeWithText(str(R.string.files_send_file_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("'/repo/a'\\''b.kt'")
        assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)
    }

}
