package io.github.shiroel.aries.speech

/** Android speech service boundary. All callbacks are delivered on the main thread. */
interface SpeechEngine {
    fun initialize(onReady: (List<SpeechVoice>?) -> Unit)
    fun speak(text: String, voice: String?, rate: Float, onStart: () -> Unit, onFinish: (SpeechResult) -> Unit)
    fun setRate(rate: Float)
    fun stop()
    fun close()
}

data class SpeechVoice(val id: String, val name: String, val languageTag: String, val network: Boolean)
enum class SpeechResult { DONE, STOPPED, FAILED, LANGUAGE_UNAVAILABLE }
