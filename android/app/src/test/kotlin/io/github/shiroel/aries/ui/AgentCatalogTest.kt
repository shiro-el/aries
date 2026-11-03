package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class AgentCatalogTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)
    private lateinit var chat: ChatModel
    private lateinit var connection: FakeConnection

    private fun open(fail: Boolean = false) {
        val core = FakeCore()
        val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        val terminal = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working)
        connection = FakeConnection(listOf(terminal), HostStructure(emptyList(), emptyList(), null))
        if (fail) connection.catalogFailure = CoreException.Closed()
        connection.catalog = AgentCatalog(
            listOf(AgentCatalogEntry("review", "/review", "Review changes", "[branch]", "/home/me/.claude/commands/review.md")),
            listOf(AgentCatalogEntry("testing", "/testing", "Check regressions", "", "/home/me/.claude/skills/testing/SKILL.md")),
        )
        compose.setContent {
            val scope = rememberCoroutineScope()
            val link = androidx.compose.runtime.remember { HostLink(host, connection, scope, { connection }) }
            chat = androidx.compose.runtime.remember { ChatModel(core, host, terminal, link).also { it.start(scope) } }
            MaterialTheme { ChatScreen(chat, {}) }
        }
    }

    @Test fun slashCompletionReplacesOnlyTheCommandAtTheCursor() {
        open()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInput("/rev keep this")
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextInputSelection(TextRange(4))
        compose.onNodeWithText("/review").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("/review keep this")
        compose.runOnIdle { assertEquals(8, chat.input.selection.start) }
        assertFalse(connection.calls.contains("sendMessage"))
    }

    @Test fun failedCollectionKeepsInputEditableAndCanBeRefreshed() {
        open(fail = true)
        val input = compose.onNodeWithContentDescription(str(R.string.chat_input_label))
        input.performTextInput("/rev")
        compose.onNodeWithText(str(R.string.chat_catalog_failed)).assertExists()
        input.assertTextEquals("/rev")
        compose.runOnIdle { connection.catalogFailure = null }
        compose.onNodeWithText(str(R.string.chat_catalog_refresh)).performClick()
        compose.onNodeWithText("/review").assertExists()
        compose.onNodeWithText(str(R.string.chat_catalog_failed)).assertDoesNotExist()
        assertEquals(listOf(false, true), connection.catalogRequests)
        assertTrue(connection.typed.isEmpty())
    }

    @Test fun skillsSearchDescriptionsAndReplaceSelectionWithoutSending() {
        open()
        compose.runOnIdle { chat.input = TextFieldValue("please OLD next", TextRange(7, 10)) }
        compose.onNodeWithText(str(R.string.chat_skills_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_skills_search)).performTextInput("regressions")
        compose.onNodeWithText("/testing").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("please /testing next")
        compose.runOnIdle { assertEquals(16, chat.input.selection.start) }
        assertFalse(connection.calls.contains("sendMessage"))
    }
}
