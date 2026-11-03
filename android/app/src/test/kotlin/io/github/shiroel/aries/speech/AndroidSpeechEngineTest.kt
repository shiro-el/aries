package io.github.shiroel.aries.speech

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/** System speech boundary: language fallback, Android-sized chunks, completion, native cleanup. */
@RunWith(RobolectricTestRunner::class)
class AndroidSpeechEngineTest {
    @Test fun longKoreanResponsesUseAnInstalledKoreanVoiceAndReachTheEndWithoutLosingUnicode() {
        val english = Voice("en", Locale.US, 300, 300, false, emptySet())
        val korean = Voice("ko", Locale.KOREA, 300, 300, false, emptySet())
        val missing = Voice("missing", Locale.KOREA, 300, 300, true, setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        listOf(english, korean, missing).forEach(ShadowTextToSpeech::addVoice)
        val engine = AndroidSpeechEngine(ApplicationProvider.getApplicationContext<Context>())
        var voices: List<SpeechVoice>? = null
        engine.initialize { voices = it }
        val platform = ShadowTextToSpeech.getLastTextToSpeechInstance()
        val speech = shadowOf(platform)
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("en", "ko"), voices!!.map { it.id }.toSet())
        val text = "안녕하세요. " + "a".repeat(TextToSpeech.getMaxSpeechInputLength() - 9) + "🙂".repeat(5000) + "끝"
        val results = mutableListOf<SpeechResult>()
        engine.speak(text, "en", 1.25f, {}, results::add)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("ko", speech.currentVoice.name)
        assertTrue(speech.spokenTextList.size > 2)
        assertEquals(text, speech.spokenTextList.joinToString(""))
        assertTrue(speech.spokenTextList.all { it.length <= TextToSpeech.getMaxSpeechInputLength() && !it.last().isHighSurrogate() })
        assertEquals(listOf(SpeechResult.DONE), results)
        engine.close()
        assertTrue(speech.isShutdown)
    }
}
