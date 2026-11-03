package io.github.shiroel.aries.speech

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import io.github.shiroel.aries.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: actual Chat/Settings navigation; only the Android speech service is faked. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ReadAloudUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeSpeechEngine()
    private val core = FakeCore()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun openApp() {
        context.getSharedPreferences("read-aloud", Context.MODE_PRIVATE).edit().clear().commit()
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            CompositionLocalProvider(LocalSpeechEngineFactory provides { engine }) { AriesApp(model) }
        }
    }

    private fun openChat() {
        openApp()
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
    }

    @Test fun responseOnlyReadsWhenTappedAndStopSurvivesLateCallbacksAndStreaming() {
        openChat()
        fun show(text: String) {
            core.pushChat(ChatSnapshot(listOf(ChatEntry("reply", null, ChatEntryBody.Agent(text))), ChatStatus.LIVE, null))
            compose.waitForIdle()
        }
        show("**Hello**\n\n```sh\nsecret command\n```")
        assertTrue(engine.spoken.isEmpty())
        compose.onNodeWithTag("read-aloud:reply").performScrollTo().performClick()
        assertEquals("Hello", engine.spoken.single())
        compose.onNodeWithText(str(R.string.read_aloud_preparing)).assertExists()
        val oldStart = engine.started
        val oldFinish = engine.finished
        compose.runOnIdle { engine.started() }
        compose.onNodeWithText(str(R.string.read_aloud_playing)).assertExists()
        show("**Hello updated**")
        assertEquals(1, engine.spoken.size)
        compose.onNodeWithTag("read-aloud-stop").performClick()
        compose.runOnIdle { oldStart(); oldFinish(SpeechResult.DONE) }
        compose.onNodeWithTag("read-aloud-stop").assertDoesNotExist()
        assertTrue(engine.stops > 0)
        compose.onNodeWithTag("read-aloud:reply").performScrollTo().performClick()
        assertEquals("Hello updated", engine.spoken.last())
        compose.onNodeWithText(str(R.string.agent_view_terminal)).performClick()
        compose.runOnIdle { assertTrue(engine.closed) }
    }
    @Test
    @Config(qualifiers = "ko-rKR-h1200dp")
    fun settingsPersistVoiceAndSpeedAndPreviewStopsOnExit() {
        openApp()
        fun settings() {
            compose.openConsoleDrawer()
            compose.onNodeWithTag("settings-open").performClick()
            compose.onNode(hasScrollToKeyAction()).performScrollToKey("read-aloud")
            compose.onNodeWithTag("read-aloud-voice").performScrollTo()
        }
        settings()
        assertTrue(engine.spoken.isEmpty())
        compose.onNodeWithTag("read-aloud-speed").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.onNodeWithTag("read-aloud-voice").performClick()
        compose.onNodeWithText(str(R.string.read_aloud_voice_label, "English", "en-US", str(R.string.read_aloud_voice_local))).performClick()
        compose.onNodeWithTag("read-aloud-preview").performScrollTo().performClick()
        assertEquals("Hello. Aries can read Agent responses aloud.", engine.spoken.single())
        assertEquals("en", engine.lastVoice)
        assertEquals(1.5f, engine.lastRate)
        compose.runOnIdle { engine.finished(SpeechResult.FAILED) }
        compose.onNodeWithText(str(R.string.read_aloud_failed)).assertExists()
        compose.onNodeWithTag("read-aloud-preview").performClick()
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.runOnIdle { assertTrue(engine.closed) }
        settings()
        compose.onNodeWithText(str(R.string.read_aloud_voice, "English (en-US)")).assertExists()
        compose.onNodeWithTag("read-aloud-preview").performScrollTo().performClick()
        assertEquals(1.5f, engine.lastRate)
        assertEquals("en", engine.lastVoice)
    }

}

class FakeSpeechEngine : SpeechEngine {
    val spoken = mutableListOf<String>()
    var started: () -> Unit = {}
    var finished: (SpeechResult) -> Unit = {}
    var lastVoice: String? = null
    var lastRate = 1f
    var stops = 0
    var closed = false
    override fun initialize(onReady: (List<SpeechVoice>?) -> Unit) {
        closed = false
        onReady(listOf(SpeechVoice("en", "English", "en-US", false), SpeechVoice("ko", "한국어", "ko-KR", false)))
    }
    override fun speak(text: String, voice: String?, rate: Float, onStart: () -> Unit, onFinish: (SpeechResult) -> Unit) {
        spoken += text; started = onStart; finished = onFinish
        lastVoice = voice; lastRate = rate
    }
    override fun setRate(rate: Float) = Unit
    override fun stop() { stops++ }
    override fun close() { closed = true; stop() }
}
