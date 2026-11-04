package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** IME editing through Chat's UI/core seam; no microphone or speech service is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ChatImeTest {
    @get:Rule val compose = createComposeRule()
    private val core = FakeCore()
    private val terminal = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
    private lateinit var chat: ChatModel
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val input get() = compose.onNodeWithContentDescription(context.getString(R.string.chat_input_label))

    private fun open() {
        val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val link = remember {
                val connection = FakeConnection(listOf(terminal), HostStructure(emptyList(), emptyList(), null))
                HostLink(host, connection, scope, { connection })
            }
            chat = remember { ChatModel(core, host, terminal, link).also { it.start(scope) } }
            MaterialTheme { ChatScreen(chat, {}) }
        }
        input.performClick()
    }

    @Test fun snapshotAndSameTextDraftWriteKeepVoiceCompositionAndSelection() {
        open()
        val composing = TextFieldValue("고쳐 주세요", TextRange(3), TextRange(0, 6))
        compose.runOnIdle { chat.input = composing }
        compose.runOnIdle {
            core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, "c:1"))
            chat.update(listOf(terminal.copy(agentStatus = AgentStatus.Idle)))
            chat.draft = chat.draft
        }
        compose.waitUntil { chat.snapshot?.cursor == "c:1" }
        input.assertTextEquals("고쳐 주세요").assertIsFocused().assertIsEnabled()
        compose.runOnIdle { assertEquals(composing, chat.input) }
    }
}
