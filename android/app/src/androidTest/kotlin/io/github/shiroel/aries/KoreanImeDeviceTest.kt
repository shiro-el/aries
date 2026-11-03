package io.github.shiroel.aries

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalKey
import io.github.shiroel.aries.ui.TerminalInput
import io.github.shiroel.aries.ui.TerminalInputView
import io.github.shiroel.aries.ui.TerminalScreen
import io.github.shiroel.aries.ui.TerminalViewer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Korean typed on the real Gboard (two-set 두벌식 layout) into the Terminal's
 * input, by tapping its keys. Needs Gboard as the current keyboard with
 * 한국어 두벌식 and English (US) QWERTY added, on a 1080x2400 screen (the key
 * positions); run by `scripts/test-korean-ime.sh`, which passes `koreanIme`.
 * Skipped otherwise. No Host: it checks what reaches [TerminalViewer.inputs].
 */
@RunWith(AndroidJUnit4::class)
class KoreanImeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sent = mutableListOf<String>()
    private lateinit var viewer: TerminalViewer

    @Test
    fun koreanOnGboard() {
        assumeTrue("no koreanIme argument", InstrumentationRegistry.getArguments().getString("koreanIme") != null)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        assumeTrue("key positions are for 1080x2400", metrics.widthPixels == 1080 && metrics.heightPixels in 2337..2400)
        viewer = TerminalViewer(TerminalInfo("t1", "sh", "", listOf("sh"), "/", "live", AgentStatus.Working, false, 0uL))
        compose.setContent { MaterialTheme { TerminalScreen(viewer, onBack = {}) } }
        compose.runOnUiThread { findInput(compose.activity.window.decorView)!!.showKeyboard() }
        SystemClock.sleep(2000)

        // Find out which language is on: ㅎ composes in Korean, g commits in English.
        tap("g")
        if (viewer.composing.isEmpty()) {
            tap(BACKSPACE)
            tap(GLOBE)
        } else {
            tap(BACKSPACE)
        }
        drain()
        sent.clear()

        // 한국: each syllable is held until the next one starts, then sent.
        tap("g"); tap("k"); tap("s") // ㅎ ㅏ ㄴ
        assertEquals("한", viewer.composing)
        assertEquals(emptyList<String>(), drain())
        tap("r"); tap("n"); tap("r") // ㄱ ㅜ ㄱ
        assertEquals("국", viewer.composing)
        tap(SPACE)
        assertEquals("한국 ", screen())

        // Backspace while composing takes jamo off; nothing reaches the Terminal.
        tap("r"); tap("k"); tap("s") // 간
        tap(BACKSPACE); tap(BACKSPACE); tap(BACKSPACE)
        assertEquals("", viewer.composing)
        assertEquals("한국 ", screen())
        // With nothing composing, Backspace deletes in the Terminal.
        tap(BACKSPACE)
        assertEquals("한국", screen())

        // A key bar key while composing: the syllable goes first, and the
        // keyboard starts afresh (no 마 again).
        tap("a"); tap("k") // 마
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.key_left)).performClick()
        SystemClock.sleep(KEY_MS)
        tap("r"); tap("k"); tap(SPACE) // 가
        assertEquals("한국마<Left>가 ", screen())

        // Enter while composing: the syllable, then Enter.
        tap("d"); tap("y") // 요
        tap(ENTER)
        assertEquals("한국마<Left>가 요<Enter>", screen())

        // English: each key reaches the Terminal right away.
        tap("d"); tap("p") // 에 (held)
        tap(GLOBE)
        assertEquals("the language key finishes the syllable", "", viewer.composing)
        tap(SPACE)
        tap("l")
        assertEquals("한국마<Left>가 요<Enter>에 l", screen())
        tap("s")
        assertEquals("한국마<Left>가 요<Enter>에 ls", screen())
        tap(ENTER)
        assertEquals("한국마<Left>가 요<Enter>에 ls<Enter>", screen())
        Log.i("AriesKoreanIme", "sent: $sent")
        tap(GLOBE) // back to Korean, as it started
    }

    /** What was sent, rendered as a line editor would: Backspace removes the last character. */
    private fun screen(): String {
        drain()
        val out = StringBuilder()
        for (s in sent) {
            when {
                s.startsWith("text:") -> out.append(s.removePrefix("text:"))
                s == "key:Backspace" -> if (out.isNotEmpty()) out.setLength(out.offsetByCodePoints(out.length, -1))
                else -> out.append("<").append(s.removePrefix("key:")).append(">")
            }
        }
        return out.toString()
    }

    private fun drain(): List<String> {
        val new = mutableListOf<String>()
        compose.runOnUiThread {
            while (true) {
                val input = viewer.inputs.tryReceive().getOrNull() ?: break
                new += when (input) {
                    is TerminalInput.Text -> "text:${input.text}"
                    is TerminalInput.Key -> "key:" + when (val k = input.key) {
                        is TerminalKey.Char -> k.text
                        else -> k::class.simpleName
                    }
                }
            }
        }
        sent += new
        return new
    }

    /** Taps the key at [key] (a QWERTY letter, which is also its 두벌식 jamo, or a special key). */
    private fun tap(key: String) {
        val (x, y) = position(key)
        instrumentation.uiAutomation.executeShellCommand("input tap $x $y").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
        }
        SystemClock.sleep(KEY_MS)
        compose.waitForIdle()
    }

    private fun position(key: String): Pair<Int, Int> {
        ROWS.forEachIndexed { row, letters ->
            val i = letters.indexOf(key)
            if (i >= 0) return (ROW_X[row] + KEY_W * i) to ROW_Y[row]
        }
        return when (key) {
            BACKSPACE -> 994 to 2024
            GLOBE -> 325 to 2180
            SPACE -> 593 to 2180
            ENTER -> 994 to 2180
            else -> error("no key $key")
        }
    }

    private fun findInput(v: View): TerminalInputView? = when (v) {
        is TerminalInputView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findInput(v.getChildAt(it)) }
        else -> null
    }

    private companion object {
        const val KEY_MS = 400L
        const val BACKSPACE = "<bs>"
        const val GLOBE = "<globe>"
        const val SPACE = "<space>"
        const val ENTER = "<enter>"
        // Gboard's QWERTY on a 1080x2400 screen, key centers in px.
        val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val ROW_X = listOf(58, 112, 218)
        val ROW_Y = listOf(1716, 1868, 2024)
        const val KEY_W = 107
    }
}
