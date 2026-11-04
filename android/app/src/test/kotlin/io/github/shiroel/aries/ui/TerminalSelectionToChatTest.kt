package io.github.shiroel.aries.ui

import androidx.compose.ui.test.onNodeWithContentDescription

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Selection gestures and draft routing at the Android UI/core seam. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "h1200dp")
class TerminalSelectionToChatTest {
    @get:Rule val compose = createComposeRule()
    private val terminal = TerminalInfo("remote/t7", "build", "", listOf("make"), "/repo", "live")
    private lateinit var model: HostsModel
    private lateinit var core: FakeCore
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    private fun open(info: TerminalInfo = terminal): FakeTerminalView {
        core = FakeCore().apply {
            terminals = listOf(info)
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText(info.name!!).performClick()
        return core.connection!!.views.single()
    }

    private fun selectWord(view: FakeTerminalView, text: String = "error details") {
        val run = CellRun(0u, text.length.toUShort(), text, false, null, null, false, false, false, false, false, false)
        view.push(TerminalUpdate.Screen(ScreenFrame(40u, 2u, true, listOf(ScreenLine(0u, listOf(run))), null, null)))
        compose.waitForIdle()
        val screen = compose.onNodeWithTag("terminal-screen")
        val width = screen.fetchSemanticsNode().size.width
        screen.performTouchInput { longClick(Offset(1.5f * width / 40, 1f)) }
    }

    @Test
    fun typingSelectionKeepsItsTextAcrossOutputSearchAndResizeUntilSent() {
        open()
        compose.runOnIdle { (model.screen as Screen.Terminal).viewer.startTyping() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val view = core.connection!!.views.last()
        assertFalse(view.options.observe)
        selectWord(view)
        fun shot(name: String) {
            val file = java.io.File("build/reports/terminal-size/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("typing-selected")
        val text = "replacement output"
        view.push(TerminalUpdate.Screen(ScreenFrame(60u, 4u, true,
            listOf(ScreenLine(0u, listOf(CellRun(0u, text.length.toUShort(), text, false, null, null,
                false, false, false, false, false, false)))), null, null, 2u, 10u,
            search = io.github.shiroel.aries.core.TerminalSearch(1u, 0u, listOf(
                io.github.shiroel.aries.core.TerminalSearchHighlight(3u, 0u, 2u, true))),
        )))
        compose.waitForIdle()
        shot("typing-selected-after-output-resize-search")
        compose.onNodeWithContentDescription(str(R.string.terminal_send_to_agent)).performClick()
        compose.onNodeWithTag("terminal-composer").assertTextEquals("```\nerror\n```")
        compose.runOnIdle { assertTrue(view.sent.isEmpty()) }
    }

    @Test
    fun terminalWithoutAgentAppendsSelectedCodeToItsComposerWithoutTyping() {
        val view = open()
        lateinit var viewer: TerminalViewer
        compose.runOnIdle {
            viewer = (model.screen as Screen.Terminal).viewer
            viewer.draft = "Explain this"
        }
        selectWord(view)
        compose.onNodeWithContentDescription(str(R.string.terminal_send_to_agent)).performClick()
        compose.onNodeWithTag("terminal-composer").assertTextEquals("Explain this\n\n```\nerror\n```")
        compose.runOnIdle {
            assertSame(viewer, (model.screen as Screen.Terminal).viewer)
            assertTrue(viewer.composerOpen)
            assertFalse(viewer.typing)
            assertTrue(view.sent.isEmpty())
            assertTrue(core.connection!!.typed.isEmpty())
        }
        compose.onNodeWithContentDescription(str(R.string.terminal_send_to_agent)).assertDoesNotExist()
    }

    @Test
    fun agentSelectionReturnsToItsOwnChatAndPreservesTheDraftAcrossViewSwitches() {
        val view = open(terminal.copy(agentStatus = AgentStatus.Working))
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        compose.runOnIdle { (model.screen as Screen.Chat).chat.draft = "Why did this fail?" }
        compose.onNodeWithContentDescription(str(R.string.agent_switch_terminal_label)).performClick()
        val currentView = core.connection!!.views.last()
        selectWord(currentView)
        compose.onNodeWithContentDescription(str(R.string.terminal_send_to_agent)).performClick()
        compose.runOnIdle {
            val chat = (model.screen as Screen.Chat).chat
            assertEquals("h1", chat.host.id)
            assertEquals("remote/t7", chat.terminal.address)
            assertEquals("Why did this fail?\n\n```\nerror\n```", chat.draft)
            assertTrue(core.connection!!.typed.isEmpty())
            assertTrue(view.sent.isEmpty())
            assertTrue(currentView.sent.isEmpty())
            assertTrue(currentView.detached)
        }
    }

    @Test
    fun agentTerminalCaptureReturnsToItsOwnChatAndKeepsTheExistingDraft() {
        open(terminal.copy(agentStatus = AgentStatus.Idle))
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        compose.runOnIdle { (model.screen as Screen.Chat).chat.draft = "Existing draft" }
        compose.onNodeWithContentDescription(str(R.string.agent_switch_terminal_label)).performClick()
        val file = java.io.File(context.cacheDir, "preview-routing.png")
        android.graphics.Bitmap.createBitmap(20, 10, android.graphics.Bitmap.Config.ARGB_8888).apply {
            file.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val captureContext = "Preview: http://127.0.0.1:5173/settings\nView mode: Desktop view\n\nFix the spacing"
        compose.runOnIdle {
            assertTrue(model.attachPreviewToChat(context, SharedContent(listOf(android.net.Uri.fromFile(file)), captureContext)) { file.delete() })
        }
        compose.waitUntil(5_000) { core.connection!!.uploads.isNotEmpty() }
        compose.waitForIdle()
        compose.runOnIdle {
            val chat = (model.screen as Screen.Chat).chat
            assertEquals("h1", chat.host.id)
            assertEquals("remote/t7", chat.terminal.address)
            assertEquals("Existing draft $captureContext", chat.draft)
            assertEquals("remote/t7", core.connection!!.uploads.single().first)
            assertEquals(1, chat.attachments.size)
            assertTrue(core.connection!!.typed.isEmpty())
            assertTrue(!file.exists())
        }
    }

    @Test
    fun multilineSelectionKeepsIndentationAndUsesAFenceLongerThanItsContents() {
        val view = open()
        val lines = listOf("failure", "```", "  details").mapIndexed { row, text ->
            ScreenLine(row.toUShort(), listOf(CellRun(0u, text.length.toUShort(), text, false, null, null,
                false, false, false, false, false, false)))
        }
        view.push(TerminalUpdate.Screen(ScreenFrame(40u, 3u, true, lines, null, null)))
        compose.waitForIdle()
        compose.onNodeWithTag("terminal-screen").performTouchInput {
            down(Offset(1f, 1f))
            advanceEventTime(1_000)
            moveTo(bottomRight - Offset(1f, 1f), delayMillis = 500)
            up()
        }
        compose.onNodeWithContentDescription(str(R.string.terminal_send_to_agent)).performClick()
        compose.onNodeWithTag("terminal-composer").assertTextEquals("````\nfailure\n```\n  details\n````")
        compose.runOnIdle { assertTrue(view.sent.isEmpty()) }
    }

    @Test
    fun terminalOpenedOverALiveChatAppendsToThatExistingChat() {
        open(terminal.copy(agentStatus = AgentStatus.Working))
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        lateinit var chat: ChatModel
        compose.runOnIdle {
            chat = (model.screen as Screen.Chat).chat
            chat.draft = "Existing draft"
            model.openTerminal(chat.terminal)
        }
        val view = core.connection!!.views.last()
        selectWord(view)
        compose.onNodeWithContentDescription(str(R.string.terminal_send_to_agent)).performClick()
        compose.runOnIdle {
            assertSame(chat, (model.screen as Screen.Chat).chat)
            assertEquals("Existing draft\n\n```\nerror\n```", chat.draft)
            assertTrue(core.connection!!.typed.isEmpty())
            assertTrue(view.detached)
        }
    }
}
