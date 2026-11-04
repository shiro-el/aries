package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private val connection = FakeConnection(listOf(terminal), HostStructure(emptyList(), emptyList(), null))
    private lateinit var chat: ChatModel
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val input get() = compose.onNodeWithContentDescription(context.getString(R.string.chat_input_label))

    private fun open(compact: Boolean = false, fontScale: Float = 1f) {
        val host = core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val link = remember {
                HostLink(host, connection, scope, { connection })
            }
            chat = remember { ChatModel(core, host, terminal, link).also { it.start(scope) } }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(if (compact) Modifier.height(360.dp) else Modifier) { ChatScreen(chat, {}) }
                }
            }
        }
        input.performClick()
    }

    // A compact viewport must not collapse multiline drafts back to a single visible line.
    @Test fun compactDraftGrowsFromOneToThreeLines() = checkCompactGrowth(1f)

    @Test fun compactDraftGrowsWithLargeText() = checkCompactGrowth(1.6f)

    private fun checkCompactGrowth(fontScale: Float) {
        open(compact = true, fontScale = fontScale)
        input.performTextInput("First line")
        val singleLineHeight = input.fetchSemanticsNode().boundsInRoot.height
        input.performTextInput("\nSecond line\nThird line")
        assertTrue("Three lines must grow the compact composer", input.fetchSemanticsNode().boundsInRoot.height > singleLineHeight)
        input.assertIsFocused().assertTextEquals("First line\nSecond line\nThird line")
    }

    // Overflow must scroll the editor, and moving the caret must reveal either end of a long draft.
    @Test fun longCompactDraftStaysBoundedAndFollowsTheCaret() {
        open(compact = true, fontScale = 1.6f)
        input.performTextInput((1..12).joinToString("\n") { "Line $it" })
        val cappedHeight = input.fetchSemanticsNode().boundsInRoot.height
        input.performTextInput("\nAnother line")
        assertEquals(cappedHeight, input.fetchSemanticsNode().boundsInRoot.height, 0.5f)
        fun scroll() = input.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Long drafts must scroll inside the composer", scroll().maxValue() > 0f)

        input.performTextInputSelection(TextRange(0))
        compose.waitForIdle()
        assertEquals(0f, scroll().value(), 0.5f)
        input.performTextInputSelection(TextRange(chat.draft.length))
        compose.waitForIdle()
        assertTrue("The caret at the end must scroll into view", scroll().value() > 0f)
        val layouts = mutableListOf<TextLayoutResult>()
        input.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val caret = layouts.single().getCursorRect(chat.draft.length)
        assertTrue(caret.top - scroll().value() >= 0f)
        assertTrue(caret.bottom - scroll().value() <= cappedHeight)
        val endScroll = scroll().value()
        input.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue("Swiping the empty part of the input must also scroll the draft", scroll().value() < endScroll)
        input.assertIsFocused()
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

    @Test fun externalDraftReplacementCannotOverwriteAnActiveImeComposition() {
        open()
        val composing = TextFieldValue("고쳐 주세요", TextRange(3), TextRange(0, 6))
        compose.runOnIdle { chat.input = composing }
        compose.runOnIdle { chat.draft = "an older saved draft" }
        input.assertTextEquals("고쳐 주세요").assertIsFocused()
        compose.runOnIdle {
            assertEquals(composing, chat.input)
            // The keyboard commits its final recognition before another external edit.
            chat.input = TextFieldValue("고쳐 주세요!", TextRange(7))
            chat.draft = "an older saved draft"
        }
        input.assertTextEquals("an older saved draft")
        compose.runOnIdle { assertEquals(TextRange(20), chat.input.selection) }
    }

    @Test fun explicitSendCommitsVoiceTextAndClearsTheDraft() {
        open()
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, null))
        compose.waitUntil { chat.snapshot?.status == ChatStatus.LIVE }
        compose.runOnIdle {
            chat.update(listOf(terminal.copy(agentStatus = AgentStatus.Idle)))
            chat.input = TextFieldValue("고쳐 주세요", TextRange(6), TextRange(0, 6))
        }
        compose.onNodeWithText(context.getString(R.string.chat_send_button)).performClick()
        compose.waitUntil(5_000) { connection.typed == listOf("t1" to "고쳐 주세요") }
        compose.runOnIdle {
            assertEquals(listOf("t1" to "고쳐 주세요"), connection.typed)
            assertEquals(TextFieldValue(""), chat.input)
        }
        input.assertIsFocused()
    }
}
