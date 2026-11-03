package io.github.shiroel.aries.ui

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Seam 3: a Korean keyboard's composing, committing and deleting, driven
 * through the Terminal's InputConnection against a fake core. The call
 * sequences are the ones Gboard and Samsung Keyboard make (two-set layout).
 */
@RunWith(RobolectricTestRunner::class)
class KoreanImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val agent = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)
    private lateinit var core: FakeCore
    private lateinit var view: TerminalInputView
    private val attrs = EditorInfo()

    private fun focusInput(): InputConnection {
        core = FakeCore()
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
        fun find(v: View): TerminalInputView? = when (v) {
            is TerminalInputView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        view = find(compose.activity.window.decorView) ?: error("no Terminal input on screen")
        compose.runOnUiThread { view.performClick() }
        compose.waitForIdle()
        return view.onCreateInputConnection(attrs)!!
    }

    private fun sent(): List<String> {
        compose.waitForIdle()
        return core.connection!!.views.last().sent
    }

    @Test
    fun theKeyboardIsAskedForPlainTextSoItOffersKorean() {
        focusInput()
        val variation = attrs.inputType and InputType.TYPE_MASK_VARIATION
        assertEquals(InputType.TYPE_CLASS_TEXT, attrs.inputType and InputType.TYPE_MASK_CLASS)
        // Visible-password (and other password variations) make keyboards Latin-only.
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, variation)
        assertNotEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, variation)
        assertTrue("no word suggestions", attrs.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue("no auto-capitalization", attrs.inputType and InputType.TYPE_MASK_FLAGS and
            (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS) == 0)
        // What is typed into a Terminal (sudo passwords too) is not learned.
        assertTrue(attrs.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    @Test
    fun aSyllableIsHeldWhileComposingAndSentWhenCommitted() {
        val ic = focusInput()
        // 한국 on Gboard: one syllable composes at a time.
        ic.setComposingText("ㅎ", 1)
        ic.setComposingText("하", 1)
        ic.setComposingText("한", 1)
        assertEquals("nothing sent while the syllable can still change", emptyList<String>(), sent())
        assertEquals("한", view.viewer.composing)
        compose.onNodeWithText("한").assertExists()
        ic.commitText("한", 1)
        ic.setComposingText("ㄱ", 1)
        ic.setComposingText("구", 1)
        ic.setComposingText("국", 1)
        ic.finishComposingText()
        assertEquals(listOf("text:한", "text:국"), sent())
        assertEquals("", view.viewer.composing)
    }

    @Test
    fun aFinalConsonantMovesToTheNextSyllable() {
        val ic = focusInput()
        // 하나 on Samsung Keyboard: 한 + ㅏ splits into 하 + 나, in one composing text.
        ic.setComposingText("ㅎ", 1)
        ic.setComposingText("하", 1)
        ic.setComposingText("한", 1)
        ic.setComposingText("하나", 1)
        assertEquals(listOf("text:하"), sent())
        assertEquals("나", view.viewer.composing)
        ic.setComposingText("하나ㄹ", 1)
        ic.setComposingText("하나라", 1)
        ic.commitText("하나라", 1)
        assertEquals(listOf("text:하", "text:나", "text:라"), sent())
    }

    @Test
    fun backspaceWhileComposingUndoesJamoNotTheTerminal() {
        val ic = focusInput()
        ic.commitText("가", 1)
        ic.setComposingText("ㄴ", 1)
        ic.setComposingText("나", 1)
        ic.setComposingText("낙", 1)
        // The keyboard takes the jamo back off the syllable itself...
        ic.setComposingText("나", 1)
        ic.setComposingText("ㄴ", 1)
        // ...and ends the composition when it is empty.
        ic.setComposingText("", 1)
        ic.finishComposingText()
        assertEquals("only the committed syllable reached the Terminal", listOf("text:가"), sent())
        // With nothing composing, Backspace deletes in the Terminal.
        ic.deleteSurroundingText(1, 0)
        assertEquals(listOf("text:가", "key:Backspace"), sent())
    }

    @Test
    fun aDeleteInsideACompositionTrimsIt() {
        val ic = focusInput()
        ic.setComposingText("하나", 1)
        ic.deleteSurroundingText(1, 0)
        ic.finishComposingText()
        assertEquals(listOf("text:하"), sent())
    }

    @Test
    fun aSyllableHeldWhenAKeyBarKeyIsPressedIsSentFirst() {
        val ic = focusInput()
        ic.setComposingText("한", 1)
        var reset = 0
        view.viewer.onCompositionInterrupted = { reset++ }
        compose.onNodeWithText(compose.activity.getString(R.string.key_left)).performClick()
        assertEquals(listOf("text:한", "key:Left"), sent())
        assertEquals("the keyboard is told its composition ended", 1, reset)
        // The keyboard starts afresh: its next syllable is not a repeat of 한.
        ic.setComposingText("ㄱ", 1)
        ic.commitText("가", 1)
        assertEquals(listOf("text:한", "key:Left", "text:가"), sent())
    }

    @Test
    fun enterWhileComposingSendsTheSyllableThenEnter() {
        val ic = focusInput()
        ic.setComposingText("요", 1)
        // Some keyboards send Enter without finishing the composition first.
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        ic.finishComposingText()
        assertEquals(listOf("text:요", "key:Enter"), sent())
    }

    @Test
    fun switchingToEnglishFinishesTheSyllableAndLatinTypesKeyByKey() {
        val ic = focusInput()
        ic.setComposingText("네", 1)
        // The language key: the keyboard finishes what it composed.
        ic.finishComposingText()
        // Gboard commits each Latin key; Samsung Keyboard may compose the word.
        ic.commitText(" ", 1)
        ic.setComposingText("l", 1)
        assertEquals(listOf("text:네", "text: ", "text:l"), sent())
        ic.setComposingText("ls", 1)
        ic.commitText("ls", 1)
        ic.commitText("a", 1)
        assertEquals(listOf("text:네", "text: ", "text:l", "text:s", "text:a"), sent())
    }

    @Test
    fun aComposedLatinWordTheKeyboardRewritesIsCorrectedInTheTerminal() {
        val ic = focusInput()
        ic.setComposingText("t", 1)
        ic.setComposingText("te", 1)
        ic.setComposingText("teh", 1)
        // Autocorrect on commit.
        ic.commitText("the", 1)
        assertEquals(
            listOf("text:t", "text:e", "text:h", "key:Backspace", "key:Backspace", "text:he"),
            sent(),
        )
    }
}
