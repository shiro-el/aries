package io.github.shiroel.aries.dictation

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.ceil

sealed class DictationFailure : Exception() {
    class RateLimited(val seconds: Long) : DictationFailure()
    data object TooLarge : DictationFailure()
    data object EmptyAudio : DictationFailure()
    data object Unauthorized : DictationFailure()
    data object Network : DictationFailure()
    data object InvalidResponse : DictationFailure()
    class Http(val status: Int) : DictationFailure()
}

fun interface Transcriber {
    /** Blocking; callers run off the main thread. Errors never contain response bodies/keys. */
    fun transcribe(audio: File, key: String, options: DictationOptions, prompt: String): String
}

/** File transcription only. No logging, redirects, translation, or automatic retry. */
class GroqTranscriber(
    private val endpoint: String = "https://api.groq.com/openai/v1/audio/transcriptions",
    private val now: () -> Long = System::currentTimeMillis,
) : Transcriber {
    private var retryAt = 0L

    override fun transcribe(audio: File, key: String, options: DictationOptions, prompt: String): String {
        if (now() < retryAt) throw DictationFailure.RateLimited(ceil((retryAt - now()) / 1000.0).toLong())
        if (audio.length() > MAX_AUDIO_BYTES) throw DictationFailure.TooLarge
        if (!audio.isFile || audio.length() == 0L) throw DictationFailure.EmptyAudio
        require(key.isNotBlank() && !key.contains('\r') && !key.contains('\n'))
        val boundary = "aries-${UUID.randomUUID()}"
        val webm = audio.extension.lowercase() == "webm"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false // never forward Authorization to another origin
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.doOutput = true
            conn.setChunkedStreamingMode(16 * 1024)
            conn.outputStream.use { output ->
                fun write(text: String) = output.write(text.toByteArray(Charsets.UTF_8))
                fun field(name: String, value: String) {
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                field("model", options.model.apiName)
                field("response_format", "json")
                options.language?.let { field("language", it) }
                if (prompt.isNotBlank()) field("prompt", DictationPrompt.build(prompt, emptyList(), "", false))
                write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dictation.${if (webm) "webm" else "m4a"}\"\r\nContent-Type: ${if (webm) "audio/webm" else "audio/mp4"}\r\n\r\n")
                audio.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > MAX_AUDIO_BYTES) throw DictationFailure.TooLarge
                        output.write(buffer, 0, size)
                    }
                }
                write("\r\n--$boundary--\r\n")
            }
            when (val status = conn.responseCode) {
                200 -> {
                    val bytes = conn.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= MAX_RESPONSE_BYTES) {
                            val size = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                            if (size < 0) break
                            output.write(buffer, 0, size)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size > MAX_RESPONSE_BYTES) throw DictationFailure.InvalidResponse
                    return try {
                        val json = JSONObject(bytes.toString(Charsets.UTF_8))
                        (json.get("text") as? String)?.trim() ?: throw DictationFailure.InvalidResponse
                    } catch (_: org.json.JSONException) { throw DictationFailure.InvalidResponse }
                }
                401, 403 -> throw DictationFailure.Unauthorized
                413 -> throw DictationFailure.TooLarge
                429 -> {
                    val header = conn.getHeaderField("Retry-After")
                    val seconds = header?.toLongOrNull()?.coerceIn(1, MAX_RETRY_SECONDS)
                        ?: runCatching {
                            val at = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                            ceil((at - now()) / 1000.0).toLong().coerceIn(1, MAX_RETRY_SECONDS)
                        }.getOrDefault(60L)
                    retryAt = now() + seconds * 1000
                    throw DictationFailure.RateLimited(seconds)
                }
                else -> throw DictationFailure.Http(status)
            }
        } catch (_: IOException) {
            throw DictationFailure.Network
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MAX_AUDIO_BYTES = 25_000_000L
        private const val MAX_RESPONSE_BYTES = 1_000_000
        private const val MAX_RETRY_SECONDS = 7 * 24 * 60 * 60L
    }
}
