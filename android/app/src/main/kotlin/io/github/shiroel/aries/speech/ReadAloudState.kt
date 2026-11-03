package io.github.shiroel.aries.speech

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.shiroel.aries.R

val LocalSpeechEngineFactory = staticCompositionLocalOf<(Context) -> SpeechEngine> { { AndroidSpeechEngine(it) } }

/** One screen owns playback; callbacks from replaced or stopped requests cannot change its state. */
class ReadAloudState(private val engine: SpeechEngine, private val preferences: SharedPreferences) {
    var ready by mutableStateOf(false)
        private set
    var voices by mutableStateOf<List<SpeechVoice>>(emptyList())
        private set
    var activeId by mutableStateOf<String?>(null)
        private set
    var speaking by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var rate by mutableStateOf(preferences.getFloat("rate", 1f).takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f)
        private set
    var voice by mutableStateOf(preferences.getString("voice", null))
        private set
    private var generation = 0
    private var closed = false

    init {
        engine.initialize { available ->
            if (!closed) {
                ready = available != null
                voices = available.orEmpty()
                notice = if (ready) null else R.string.read_aloud_unavailable
            }
        }
    }

    fun read(id: String, markdown: String) {
        if (!ready || closed) return
        stop()
        val text = ReadAloudText.clean(markdown)
        if (text.isBlank()) { notice = R.string.read_aloud_empty; return }
        val current = generation
        activeId = id
        engine.speak(text, voice, rate, onStart = {
            if (!closed && generation == current) speaking = true
        }, onFinish = { result ->
            if (!closed && generation == current) {
                activeId = null
                speaking = false
                notice = when (result) {
                    SpeechResult.FAILED -> R.string.read_aloud_failed
                    SpeechResult.LANGUAGE_UNAVAILABLE -> R.string.read_aloud_language_unavailable
                    else -> null
                }
            }
        })
    }

    fun stop() {
        generation++
        activeId = null
        speaking = false
        engine.stop()
        if (ready) notice = null
    }

    fun chooseRate(value: Float) {
        if (!value.isFinite()) return
        rate = value.coerceIn(.5f, 2f)
        preferences.edit().putFloat("rate", rate).apply()
        engine.setRate(rate)
    }

    fun chooseVoice(id: String?) {
        stop()
        voice = id
        preferences.edit().putString("voice", id).apply()
    }

    fun close() {
        closed = true
        stop()
        engine.close()
    }
}

@Composable
fun rememberReadAloud(owner: Any): ReadAloudState {
    val context = LocalContext.current.applicationContext
    val factory = LocalSpeechEngineFactory.current
    val state = remember(owner, context, factory) {
        ReadAloudState(factory(context), context.getSharedPreferences("read-aloud", Context.MODE_PRIVATE))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.close() }
    }
    return state
}
