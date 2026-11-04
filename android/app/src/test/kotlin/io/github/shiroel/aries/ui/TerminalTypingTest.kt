package io.github.shiroel.aries.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AttachMode
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: typing into a Terminal (input mode, key bar), against a fake core. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h700dp")
class TerminalTypingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)

    private fun open(core: FakeCore) {
        core.terminals = listOf(agent)
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
    }

    private fun inputView(): TerminalInputView {
        fun find(v: View): TerminalInputView? = when (v) {
            is TerminalInputView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(compose.activity.window.decorView) ?: error("no Terminal input on screen")
    }

    /** Taps the input field and returns the connection the keyboard would use. */
    private fun focusInput(): InputConnection {
        val view = inputView()
        compose.runOnUiThread { view.performClick() }
        compose.waitForIdle()
        return view.onCreateInputConnection(EditorInfo())!!
    }

    private fun desktopFrame(view: FakeTerminalView) =
        view.push(TerminalUpdate.Screen(ScreenFrame(200u, 120u, true, emptyList(), null, null, 0u, 0u)))

    @Test
    fun composerHistoryRestoresTheDraftWithoutSendingAndIsSharedWithChat() {
        val core = FakeCore()
        open(core)
        val host = core.hosts().single()
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).performTextReplacement("from Chat\n한국어")
        compose.onNodeWithText(str(R.string.chat_send_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.agent_switch_terminal_label)).performClick()
        compose.onNodeWithContentDescription(str(R.string.terminal_compose_button)).performClick()
        compose.onNodeWithTag("terminal-composer").performTextReplacement("keep this command")
        compose.onNodeWithContentDescription("Input history").performClick()
        compose.onNodeWithText("from Chat\n한국어").performClick()
        compose.onNodeWithTag("terminal-composer").assertTextEquals("keep this command\n\nfrom Chat\n한국어")
        compose.onNodeWithTag("terminal-composer").performTextReplacement("edited command")
        compose.runOnIdle { assertTrue(core.connection!!.views.all { it.sent.isEmpty() }) }
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("paste:edited command"), core.connection!!.views.last().sent)
            assertEquals(listOf("edited command", "from Chat\n한국어"), core.inputHistory(host.id, "t1"))
        }
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        compose.onNodeWithContentDescription("Input history").performClick()
        compose.onNodeWithText("edited command").performClick()
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("edited command")
        assertEquals(listOf("t1" to "from Chat\n한국어"), core.connection!!.typed)
        compose.onNodeWithContentDescription("Input history").performClick()
        compose.onNodeWithText("Clear history").performClick()
        compose.onNodeWithText("No input history yet.").assertExists()
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        compose.runOnIdle { assertTrue(core.inputHistory(host.id, "t1").isEmpty()) }
        compose.onNodeWithContentDescription(str(R.string.chat_input_label)).assertTextEquals("edited command")
    }

    @Test
    fun focusingTheInputReattachesForTypingAtThePhonesSize() {
        val core = FakeCore()
        open(core)
        val watcher = core.connection!!.views.single()
        assertTrue(watcher.options.observe)
        desktopFrame(watcher)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminal_observing, "t1", 200, 120)).assertExists()

        focusInput()
        val views = core.connection!!.views
        assertEquals(2, views.size)
        assertTrue("the observer is let go", watcher.detached)
        val typing = views[1]
        assertEquals("t1", typing.address)
        assertFalse("attaches as an ordinary viewer", typing.options.observe)
        val cols = typing.options.cols.toInt()
        val rows = typing.options.rows.toInt()
        // Robolectric's glyph widths are not a phone's, so only the rows are a real phone size.
        assertTrue("the phone's grid, not the desktop's: ${cols}x$rows", cols != 200 && rows in 5..119)
        typing.push(TerminalUpdate.Screen(ScreenFrame(cols.toUShort(), rows.toUShort(), true, emptyList(), null, null, 0u, 0u)))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminal_typing, "t1", cols, rows)).assertExists()
    }


    @Test
    fun composeEditsStayLocalAndSendAsOnePasteWithoutEnter() {
        val core = FakeCore()
        open(core)
        val observer = core.connection!!.views.single()
        compose.onNodeWithContentDescription(str(R.string.terminal_compose_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).assertIsNotEnabled()
        val editor = compose.onNodeWithTag("terminal-composer")
        editor.performTextReplacement("pasted\ntext to edit")
        editor.performTextReplacement("edited\n한국어\ntext")
        compose.runOnIdle {
            assertTrue(observer.sent.isEmpty())
            assertEquals(1, core.connection!!.views.size)
        }
        // Switching input modes keeps the draft and preserves ordinary typing.
        compose.onNodeWithContentDescription(str(R.string.terminal_direct_input_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.terminal_compose_button)).performClick()
        compose.onNodeWithText("edited\n한국어\ntext").assertExists()
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).performClick()
        compose.waitForIdle()
        val typing = core.connection!!.views.last()
        assertFalse(typing.options.observe)
        assertTrue(observer.detached)
        assertEquals(listOf("paste:edited\n한국어\ntext"), typing.sent)
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.terminal_enter_button)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("paste:edited\n한국어\ntext", "key:Enter"), typing.sent)
        }
    }

    @Test
    fun typedTextAndKeyBarKeysReachTheTerminal() {
        val core = FakeCore()
        open(core)
        val ic = focusInput()
        val typing = core.connection!!.views.last()

        ic.commitText("ls -la", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        ic.commitText("x", 1)
        ic.deleteSurroundingText(1, 0)
        // Some keyboards commit Enter as a newline.
        ic.commitText("\n", 1)
        compose.onNodeWithText(str(R.string.key_esc)).performClick()
        compose.onNodeWithText(str(R.string.key_tab)).performClick()
        compose.onNodeWithText(str(R.string.key_up)).performClick()
        compose.onNodeWithText(str(R.string.key_down)).performClick()
        compose.onNodeWithText(str(R.string.key_left)).performClick()
        compose.onNodeWithText(str(R.string.key_right)).performClick()
        // Ctrl applies to the next key only, including IME commits.
        compose.onNodeWithText(str(R.string.key_ctrl)).performClick()
        ic.commitText("c", 1)
        ic.commitText("c", 1)
        compose.onNodeWithText(str(R.string.key_slash)).performClick()
        compose.onNodeWithText(str(R.string.key_ctrl)).assertIsNotSelected()
        compose.onNodeWithText(str(R.string.key_dash)).performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(
                "text:ls -la", "key:Enter", "text:x", "key:Backspace", "key:Enter",
                "key:Escape", "key:Tab", "key:Up", "key:Down", "key:Left", "key:Right",
                "key:ctrl+c", "text:c", "key:/", "key:-",
            ),
            typing.sent,
        )
    }

    @Test
    fun altAppliesOnlyToTheNextUnicodeCharacter() {
        val core = FakeCore()
        open(core)
        val ic = focusInput()
        val alt = compose.onNodeWithText(str(R.string.key_alt))
        alt.assertIsNotSelected().performClick().assertIsSelected()
        ic.commitText("😀next", 1)
        compose.waitForIdle()
        alt.assertIsNotSelected()
        ic.commitText("x", 1)
        ic.commitText("plain", 1)
        compose.waitForIdle()
        assertEquals(listOf("key:alt+😀", "text:next", "text:x", "text:plain"),
            core.connection!!.views.last().sent)
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp")
    fun navigationConsumesAltAndCtrlTogetherBeforeTheNextImeCommit() {
        val core = FakeCore()
        open(core)
        val ic = focusInput()
        for (label in listOf(R.string.key_home, R.string.key_end, R.string.key_page_up, R.string.key_page_down)) {
            compose.onNodeWithText(str(label)).assertIsDisplayed().performClick()
        }
        val alt = compose.onNodeWithText(str(R.string.key_alt))
        val ctrl = compose.onNodeWithText(str(R.string.key_ctrl))
        alt.performClick()
        ctrl.performClick()
        compose.onNodeWithText(str(R.string.key_home)).performClick()
        alt.assertIsNotSelected()
        ctrl.assertIsNotSelected()
        ic.commitText("x", 1)
        compose.waitForIdle()
        assertEquals(
            listOf("key:Home", "key:End", "key:PageUp", "key:PageDown", "key:ctrl+alt+Home", "text:x"),
            core.connection!!.views.last().sent,
        )
    }

    @Test
    fun heldHangulFinishesWithTheCurrentModifiersBeforeTheyAreToggled() {
        val core = FakeCore()
        open(core)
        val ic = focusInput()
        ic.setComposingText("한", 1)
        compose.onNodeWithText(str(R.string.key_alt)).performClick()
        ic.commitText("x", 1)
        ic.setComposingText("국", 1)
        compose.onNodeWithText(str(R.string.key_ctrl)).performClick()
        compose.onNodeWithText(str(R.string.key_alt)).performClick()
        compose.onNodeWithText(str(R.string.key_end)).performClick()
        compose.waitForIdle()
        assertEquals(
            listOf("text:한", "key:alt+x", "text:국", "key:ctrl+alt+End"),
            core.connection!!.views.last().sent,
        )
    }

    @Test
    fun heldNavigationKeysRepeatAndStopWhenReleasedOrCancelled() {
        val core = FakeCore()
        open(core)
        focusInput()
        val sent = core.connection!!.views.last().sent
        compose.mainClock.autoAdvance = false
        for ((label, key) in listOf(
            R.string.key_up to "Up", R.string.key_down to "Down",
            R.string.key_left to "Left", R.string.key_right to "Right",
            R.string.key_home to "Home", R.string.key_end to "End",
            R.string.key_page_up to "PageUp", R.string.key_page_down to "PageDown",
        )) {
            compose.runOnIdle { sent.clear() }
            val button = compose.onNodeWithText(str(label))
            button.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(900)
            compose.runOnIdle {
                assertTrue("$key should repeat while held: $sent", sent.size >= 2)
                assertTrue(sent.all { it == "key:$key" })
            }
            button.performTouchInput { up() }
            compose.waitForIdle()
            val released = sent.size
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle { assertEquals("$key stopped on release", released, sent.size) }
            button.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(900)
            button.performTouchInput { cancel() }
            compose.waitForIdle()
            val cancelled = sent.size
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle { assertEquals("$key stopped on cancel", cancelled, sent.size) }
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun tappedModifiersAreConsumedTogetherAndHidingKeysClearsUnusedModifiers() {
        val core = FakeCore()
        open(core)
        val ic = focusInput()
        val ctrl = compose.onNodeWithText(str(R.string.key_ctrl))
        val alt = compose.onNodeWithText(str(R.string.key_alt))
        ctrl.performClick().assertIsSelected()
        alt.performClick().assertIsSelected()
        ic.commitText("cx", 1)
        compose.waitForIdle()
        assertEquals(listOf("key:ctrl+alt+c", "text:x"), core.connection!!.views.last().sent)
        ctrl.assertIsNotSelected()
        alt.assertIsNotSelected().performClick().assertIsSelected()
        ic.commitText("yz", 1)
        compose.waitForIdle()
        assertEquals(listOf("key:ctrl+alt+c", "text:x", "key:alt+y", "text:z"),
            core.connection!!.views.last().sent)
        ctrl.performClick().assertIsSelected()
        alt.performClick().assertIsSelected()
        compose.onNodeWithContentDescription(str(R.string.terminal_keys_hide_button)).performClick()
        ic.commitText("q", 1)
        compose.waitForIdle()
        assertEquals("text:q", core.connection!!.views.last().sent.last())
        compose.onNodeWithContentDescription(str(R.string.terminal_keys_show_button)).performClick()
        ctrl.assertIsNotSelected()
        alt.assertIsNotSelected()
    }

    @Test
    fun withTheInputDefaultATerminalOpensReadyToType() {
        val core = FakeCore()
        core.addHost("other", Endpoint.Ssh("10.0.0.3", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        // Settings: observe by default, switch to input, then return to Hosts.
        assertEquals(AttachMode.OBSERVE, core.attachMode())
        compose.openConsoleDrawer()
        compose.onNodeWithText(str(R.string.settings_title)).performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("attach-mode")
        compose.onNodeWithText(str(R.string.attach_input_label)).performClick()
        compose.waitForIdle()
        assertEquals(AttachMode.INPUT, core.attachMode())
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()

        core.terminals = listOf(agent)
        compose.onNodeWithText("other").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        val view = core.connection!!.views.single()
        assertFalse("no observe step first", view.options.observe)
    }
}
