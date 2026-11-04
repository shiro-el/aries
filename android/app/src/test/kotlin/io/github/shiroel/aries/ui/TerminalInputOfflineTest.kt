package io.github.shiroel.aries.ui

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** UI/core seam: a dropped line must never turn old keystrokes into new commands. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalInputOfflineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: HostsModel
    private val viewer get() = (model.screen as Screen.Terminal).viewer
    private fun str(id: Int) = compose.activity.getString(id)

    private fun screenshot(name: String) {
        val directory = System.getenv("ARIES_TERMINAL_RENDER_DIR") ?: "build/reports/terminal-input-offline"
        val file = File(directory, "$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun open(core: FakeCore, typeFirstKey: Boolean = true): TerminalInputView {
        core.terminals = listOf(TerminalInfo("t1", "build", "", listOf("make"), "/repo", "live", null))
        core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val prefs = compose.activity.getSharedPreferences("terminal-input-test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = ThemeSettings(prefs).apply {
            chooseMode(if (System.getenv("ARIES_TERMINAL_THEME") == "dark") ThemeMode.DARK else ThemeMode.LIGHT)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model, settings) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("build").performClick()
        compose.waitForIdle()
        fun find(v: View): TerminalInputView? = when (v) {
            is TerminalInputView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(compose.activity.window.decorView)!!.also {
            compose.runOnUiThread { it.performClick() }
            compose.waitForIdle()
            if (!typeFirstKey) return@also
            it.onCreateInputConnection(EditorInfo())!!.commitText("x", 1)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            core.connection!!.views.last().sent.clear()
        }
    }

    // Regression: a failed typing attach on a healthy SSH line must discard its queued
    // commands and release an unconfirmed paste; Retry must require an explicit resubmit.
    @Test fun failedAttachDiscardsQueuedInputAndKeepsTheDraftForExplicitRetry() {
        val core = FakeCore()
        val input = open(core, typeFirstKey = false)
        val ime = input.onCreateInputConnection(EditorInfo())!!
        core.attachHold = CompletableDeferred()
        core.attachFailure = CoreException.Protocol("attach refused")
        compose.onNodeWithContentDescription(str(R.string.terminal_compose_button)).performClick()
        compose.onNodeWithTag("terminal-composer").performTextReplacement("review before sending")
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).performClick()
        compose.runOnIdle { ime.commitText("queued command\n", 1) }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { core.attachHold!!.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("a failed attach must disable remote input", viewer.canInput)
            assertFalse("paste cannot remain pending after attach fails", viewer.pasteSending)
            assertEquals("review before sending", viewer.draft)
            ime.commitText("typed during failure\n", 1)
            viewer.press(TerminalKey.Enter)
        }
        screenshot("attach-failed-draft-retained")
        core.attachHold = null
        core.attachFailure = null
        compose.onNodeWithText(str(R.string.connect_retry_button)).performClick()
        compose.waitForIdle()
        val fresh = core.connection!!.views.last()
        compose.runOnIdle {
            ime.commitText("late callback from the failed attachment\n", 1)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Retry must not replay any old input: ${fresh.sent}", fresh.sent.isEmpty())
            assertEquals("review before sending", viewer.draft)
        }
        screenshot("attach-retried-draft-retained")
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("paste:review before sending"), fresh.sent)
    }

    @Test fun queuedKeysAndOfflineImeInputAreNotReplayedOnTheNextConnection() {
        val core = FakeCore()
        val input = open(core)
        val ime = input.onCreateInputConnection(EditorInfo())!!
        val old = core.connection!!.views.last()
        old.textReply = CompletableDeferred()
        compose.runOnIdle {
            ime.commitText("slow write", 1)
            ime.commitText("stale command\n", 1)
        }
        compose.waitForIdle()
        core.failWith = CoreException.Unreachable("offline")
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        compose.runOnIdle {
            ime.setComposingText("한", 1)
            ime.commitText("offline command\n", 1)
            viewer.press(TerminalKey.Enter)
            viewer.draft = "keep this draft"
            viewer.pasteDraft()
        }
        compose.mainClock.advanceTimeBy(1_600)
        screenshot("offline-direct")
        compose.onNodeWithContentDescription(str(R.string.terminal_compose_button)).performClick()
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).assertIsNotEnabled()
        compose.runOnIdle { assertFalse(input.isEnabled) }
        screenshot("offline-composer")
        core.failWith = null
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        val fresh = core.connection!!.views.last()
        compose.runOnIdle {
            assertTrue("queued keys belong only to the discarded connection: ${fresh.sent}", fresh.sent.isEmpty())
            assertEquals("keep this draft", viewer.draft)
            assertEquals("", viewer.composing)
            ime.commitText("late callback from the old keyboard\n", 1)
            viewer.type("fresh\n")
        }
        compose.waitForIdle()
        assertEquals(listOf("text:fresh", "key:Enter"), fresh.sent)
        // An existing InputConnection may still deliver callbacks after the program exits.
        fresh.push(TerminalUpdate.Exited(TerminalExit("exited", 0, null)))
        compose.waitForIdle()
        compose.runOnIdle {
            ime.commitText("late input\n", 1)
            viewer.type("late direct input\n")
        }
        compose.onNodeWithContentDescription(str(R.string.terminal_enter_button)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.terminal_direct_input_button)).performClick()
        compose.onNodeWithText(str(R.string.key_ctrl)).assertIsNotEnabled()
        screenshot("program-ended")
        assertEquals(listOf("text:fresh", "key:Enter"), fresh.sent)
    }

    @Test fun aPasteInterruptedBeforeSuccessKeepsItsDraftAndRequiresAnExplicitRetry() {
        val core = FakeCore()
        open(core)
        val old = core.connection!!.views.last()
        old.pasteReply = CompletableDeferred()
        compose.onNodeWithContentDescription(str(R.string.terminal_compose_button)).performClick()
        compose.onNodeWithTag("terminal-composer").performTextReplacement("review before retry\n한국어")
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).performClick()
        compose.runOnIdle { assertEquals("review before retry\n한국어", viewer.draft) }
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).assertIsNotEnabled()

        core.failWith = CoreException.Unreachable("offline")
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        core.failWith = null
        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        val fresh = core.connection!!.views.last()
        assertTrue(fresh.sent.isEmpty())
        compose.runOnIdle { assertEquals("review before retry\n한국어", viewer.draft) }
        assertTrue(core.inputHistory(core.hosts().single().id, "t1").isEmpty())
        screenshot("paste-unconfirmed")
        compose.onNodeWithContentDescription(str(R.string.terminal_composer_send_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("paste:review before retry\n한국어"), fresh.sent)
        compose.runOnIdle { assertEquals("", viewer.draft) }
    }
}
