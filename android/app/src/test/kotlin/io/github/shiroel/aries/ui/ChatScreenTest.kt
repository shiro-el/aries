package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** An Agent's Chat, opened from the Agent list, against a fake core. */
@RunWith(RobolectricTestRunner::class)
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val claude = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)
    private val vim = TerminalInfo("t2", "vim", "", listOf("vim"), "/repo", "live", null, false, 0uL)

    private fun entry(id: String, body: ChatEntryBody) = ChatEntry(id, null, body)

    private val write = entry(
        "toolu_1",
        ChatEntryBody.Tool(
            "Write", "/repo/hello.txt", "{\n  \"file_path\": \"/repo/hello.txt\",\n  \"content\": \"hi\"\n}",
            ChatToolStatus.DONE, "File created successfully",
        ),
    )

    private fun openChat(core: FakeCore) {
        core.terminals = listOf(claude, vim)
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_open_label, "vim")).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.chat_open_label, "claude")).performClick()
        compose.waitForIdle()
    }

    @Test
    fun opensFromTheAgentListAndShowsTheConversationLive() {
        val core = FakeCore()
        openChat(core)
        assertEquals(listOf("h1" to "t1"), core.chatsOpened)
        compose.onNodeWithText(str(R.string.chat_title, "claude")).assertExists()
        compose.onNodeWithText(str(R.string.chat_status_connecting)).assertExists()

        core.pushChat(
            ChatSnapshot(
                listOf(
                    entry("u1", ChatEntryBody.User("Create hello.txt")),
                    entry("th", ChatEntryBody.Thinking(null)),
                    write,
                ),
                ChatStatus.LIVE, "c:1",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Create hello.txt").assertExists()
        compose.onNodeWithText(str(R.string.chat_thinking)).assertExists()
        compose.onNodeWithText("Write").assertExists()
        compose.onNodeWithText(str(R.string.chat_status_connecting)).assertDoesNotExist()

        // Live: the reply and an error arrive later.
        core.pushChat(
            ChatSnapshot(
                listOf(
                    entry("u1", ChatEntryBody.User("Create hello.txt")),
                    entry("th", ChatEntryBody.Thinking(null)),
                    write,
                    entry("a1", ChatEntryBody.Agent("Done.")),
                    entry("e1", ChatEntryBody.AgentError("model not found")),
                ),
                ChatStatus.LIVE, "c:2",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Done.").assertExists()
        compose.onNodeWithText("model not found").assertExists()
        compose.onNodeWithText(str(R.string.chat_agent_error)).assertExists()
    }

    @Test
    fun aToolCallIsCollapsedUntilTapped() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(write), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText("File created successfully").assertDoesNotExist()

        compose.onNodeWithContentDescription(str(R.string.chat_tool_expand_label, "Write")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("File created successfully").assertExists()
        compose.onNodeWithText(str(R.string.chat_tool_input)).assertExists()
        compose.onNodeWithText(write.body.let { (it as ChatEntryBody.Tool).input }).assertExists()

        compose.onNodeWithContentDescription(str(R.string.chat_tool_expand_label, "Write")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("File created successfully").assertDoesNotExist()
    }

    @Test
    fun showsWhyThereIsNoChatAndWhatANewerBeaconSent() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(
            ChatSnapshot(listOf(entry("q", ChatEntryBody.Unsupported)), ChatStatus.RECONNECTING, null),
        )
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_unsupported)).assertExists()
        compose.onNodeWithText(str(R.string.chat_status_reconnecting)).assertExists()

        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.NO_BEACON, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_status_no_beacon)).assertExists()
    }

    @Test
    fun opensFromTheAgentsListAndReturnsToTheAgent() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            addHost("server", Endpoint.Ssh("10.0.0.3", 22u, "me"))
            terminalsOf["h2"] = listOf(claude)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText(str(R.string.agents_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_open_label, "claude")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("h2" to "t1"), core.chatsOpened)
        compose.onNodeWithText(str(R.string.chat_title, "claude")).assertExists()

        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(core.chatStopped)
        compose.onNodeWithText(str(R.string.agent_detail_host, "server")).assertExists()
    }

    @Test
    fun backLeavesTheChatAndStopsFollowing() {
        val core = FakeCore()
        openChat(core)
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(core.chatStopped)
        compose.onNodeWithText("claude").assertExists()
        compose.onNodeWithText(str(R.string.chat_title, "claude")).assertDoesNotExist()
    }
}
