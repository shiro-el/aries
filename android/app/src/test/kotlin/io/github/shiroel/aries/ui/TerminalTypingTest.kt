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
import androidx.compose.ui.test.performClick
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

/** Seam 3: typing into a Terminal (input mode, key bar), against a fake core. */
@RunWith(RobolectricTestRunner::class)
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
        // Ctrl applies to the next key only.
        compose.onNodeWithText(str(R.string.key_ctrl)).performClick()
        ic.commitText("r", 1)
        ic.commitText("r", 1)
        compose.waitForIdle()

        assertEquals(
            listOf(
                "text:ls -la", "key:Enter", "text:x", "key:Backspace", "key:Enter",
                "key:Escape", "key:Tab", "key:Up", "key:Down", "key:Left", "key:Right",
                "key:ctrl+r", "text:r",
            ),
            typing.sent,
        )
    }

    @Test
    fun theInterruptKeySendsCtrlC() {
        val core = FakeCore()
        open(core)
        focusInput()
        compose.onNodeWithText(str(R.string.key_interrupt)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("key:ctrl+c"), core.connection!!.views.last().sent)
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
        compose.onNodeWithText(str(R.string.back_button)).performClick()

        core.terminals = listOf(agent)
        compose.onNodeWithText("other").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        val view = core.connection!!.views.single()
        assertFalse("no observe step first", view.options.observe)
    }
}
