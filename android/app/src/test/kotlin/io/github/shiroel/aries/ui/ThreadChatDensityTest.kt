package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ThreadChatDensityTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core = FakeCore()
    private val generation = mutableIntStateOf(0)

    private fun open() {
        val agent = TerminalInfo("t1", "Review", "", listOf("claude"), "/repo", "live", AgentStatus.Idle)
        core.terminals = listOf(agent)
        core.addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
        core.ensureKey()
        val appearance = ThemeSettings(context.getSharedPreferences("thread-density-test", Context.MODE_PRIVATE)).apply {
            chooseLayout(AppLayout.THREAD)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            LaunchedEffect(model) { model.openPushedAgent("h1", "t1", openChat = true) }
            key(generation.intValue) { AriesApp(model, appearance) }
        }
        compose.waitForIdle()
    }

    private fun push(entries: List<ChatEntry>) {
        core.pushChat(ChatSnapshot(entries, ChatStatus.LIVE, null))
        compose.waitForIdle()
    }

    // D72: prepending a partial turn must preserve an open fold, including after composition recreation.
    @Test fun olderHistoryKeepsTheToolFoldOpenAndStillLetsItClose() {
        fun tool(id: String) = ChatEntry(id, null, ChatEntryBody.Tool("Read", "$id.kt", "{}", ChatToolStatus.DONE, null))
        open()
        val recent = listOf(tool("recent1"), tool("recent2"))
        push(recent)
        compose.onNodeWithTag("thread-tools:recent1").performClick()
        compose.onNodeWithText("recent2.kt").assertIsDisplayed()

        push(listOf(ChatEntry("user", null, ChatEntryBody.User("Inspect the code")), tool("older")) + recent)
        compose.onNodeWithText("recent2.kt").assertIsDisplayed()
        compose.runOnIdle { generation.intValue++ }
        compose.onNodeWithText("recent2.kt").assertIsDisplayed()
        compose.onNodeWithTag("thread-tools:recent1").performClick()
        compose.onNodeWithText("recent2.kt").assertDoesNotExist()
    }
}
