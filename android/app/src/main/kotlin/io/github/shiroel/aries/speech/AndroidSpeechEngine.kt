package io.github.shiroel.aries.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** Owns one system TTS connection and transient audio focus, without a background service. */
class AndroidSpeechEngine(context: Context) : SpeechEngine {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) playback?.let { finish(it, SpeechResult.STOPPED) }
        }, main).build()
    private var tts: TextToSpeech? = null
    private var available = emptyList<Voice>()
    private var defaultVoice: Voice? = null
    private var playback: Playback? = null
    private var sequence = 0L
    private var closed = false

    private class Playback(
        val token: Long,
        val chunks: List<String>,
        val onStart: () -> Unit,
        val onFinish: (SpeechResult) -> Unit,
        var index: Int = 0,
    ) {
        val utteranceId get() = "$token:$index"
    }

    override fun initialize(onReady: (List<SpeechVoice>?) -> Unit) {
        try {
            tts = TextToSpeech(context) { status ->
                // OnInit can run before the constructor has assigned tts, and progress callbacks
                // arrive on binder threads. Serialize every event on the main looper.
                main.post {
                    if (!closed) {
                        val service = tts
                        if (status != TextToSpeech.SUCCESS || service == null) onReady(null)
                        else {
                            available = service.voices.orEmpty().filter {
                                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                            }.sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                            defaultVoice = service.voice
                            service.setAudioAttributes(attributes)
                            service.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) { event(utteranceId) { it.onStart() } }
                                override fun onDone(utteranceId: String?) { event(utteranceId) {
                                    it.index++
                                    if (it.index == it.chunks.size) finish(it, SpeechResult.DONE) else enqueue(it)
                                } }
                                @Deprecated("System callback")
                                override fun onError(utteranceId: String?) { failed(utteranceId) }
                                override fun onError(utteranceId: String?, errorCode: Int) { failed(utteranceId) }
                                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                    event(utteranceId) { finish(it, SpeechResult.STOPPED) }
                                }
                            })
                            onReady(available.map { SpeechVoice(it.name, it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired) })
                        }
                    }
                }
            }
        } catch (_: RuntimeException) { onReady(null) }
    }

    private fun failed(id: String?) = event(id) { finish(it, SpeechResult.FAILED) }
    private fun event(id: String?, action: (Playback) -> Unit) {
        main.post { playback?.takeIf { !closed && it.utteranceId == id }?.let(action) }
    }

    override fun speak(text: String, voice: String?, rate: Float, onStart: () -> Unit, onFinish: (SpeechResult) -> Unit) {
        stop()
        val service = tts
        if (closed || service == null) { onFinish(SpeechResult.FAILED); return }
        // A missing saved voice falls back to the system choice. Korean prose must not
        // be forced through an English-only voice (including when the UI is in English).
        val selected = available.find { it.name == voice } ?: defaultVoice
        val language = if (text.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }) "ko" else null
        val target = if (language != null && selected?.locale?.language != language) {
            available.filter { it.locale.language == language }.minByOrNull { if (it.isNetworkConnectionRequired) 1 else 0 }
        } else selected
        val configured = if (target != null) service.setVoice(target) == TextToSpeech.SUCCESS
            else service.setLanguage(if (language == "ko") Locale.KOREAN else Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE
        if (!configured) { onFinish(SpeechResult.LANGUAGE_UNAVAILABLE); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onFinish(SpeechResult.FAILED); return
        }
        service.setSpeechRate(rate)
        val request = Playback(++sequence, speechChunks(text, TextToSpeech.getMaxSpeechInputLength()), onStart, onFinish)
        playback = request
        enqueue(request)
    }

    private fun enqueue(request: Playback) {
        if (tts?.speak(request.chunks[request.index], TextToSpeech.QUEUE_FLUSH, null, request.utteranceId) != TextToSpeech.SUCCESS) {
            finish(request, SpeechResult.FAILED)
        }
    }

    private fun finish(request: Playback, result: SpeechResult) {
        if (playback !== request) return
        stop()
        request.onFinish(result)
    }

    override fun setRate(rate: Float) { tts?.setSpeechRate(rate) }
    override fun stop() {
        playback = null
        tts?.stop()
        audio.abandonAudioFocusRequest(focus)
    }
    override fun close() {
        closed = true
        stop()
        tts?.shutdown()
        tts = null
    }
}

/** Preserve all text, including surrogate pairs, within the system's per-utterance limit. */
internal fun speechChunks(text: String, limit: Int): List<String> {
    require(limit >= 2)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + limit).coerceAtMost(text.length)
        if (end < text.length) {
            if (text[end - 1].isHighSurrogate()) end--
            val space = text.lastIndexOfAny(charArrayOf(' ', '\n', '.', '!', '?'), end - 1)
            if (space >= start + limit / 2) end = space + 1
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
