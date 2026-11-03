package io.github.shiroel.aries.dictation

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File

/** System audio boundary; stop hands ownership of a temporary file to the caller. */
interface AudioRecorder {
    fun start(onLimit: () -> Unit)
    fun stop(): File
    fun cancel()
}

class M4aRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    @Suppress("DEPRECATION")
    override fun start(onLimit: () -> Unit) {
        cancel()
        val audio = File.createTempFile("dictation-", ".m4a", context.cacheDir)
        file = audio
        val device = MediaRecorder()
        recorder = device
        try {
            device.setAudioSource(MediaRecorder.AudioSource.MIC)
            device.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            device.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            device.setAudioChannels(1)
            device.setAudioSamplingRate(16_000)
            device.setAudioEncodingBitRate(64_000)
            device.setMaxFileSize(GroqTranscriber.MAX_AUDIO_BYTES)
            device.setOutputFile(audio.absolutePath)
            device.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    Handler(Looper.getMainLooper()).post { if (recorder === device) onLimit() }
                }
            }
            device.prepare()
            device.start()
        } catch (error: Exception) {
            cancel()
            throw error
        }
    }

    override fun stop(): File {
        val device = checkNotNull(recorder)
        try {
            device.stop()
            return checkNotNull(file).also { file = null }
        } catch (error: Exception) {
            file?.delete()
            file = null
            throw error
        } finally {
            device.release()
            recorder = null
        }
    }

    override fun cancel() {
        recorder?.let { runCatching { it.release() } }
        recorder = null
        file?.delete()
        file = null
    }
}
