package io.github.shiroel.aries.dictation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.shiroel.aries.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chat-only dictation. [onTranscript] edits the draft; it must never send a message. */
@Composable
fun DictationInput(
    names: List<String>,
    lastResponse: String,
    onTranscript: (String) -> Unit,
    store: DictationStore? = null,
    recorder: AudioRecorder? = null,
    transcriber: Transcriber? = null,
    requestPermission: (((Boolean) -> Unit) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val credentials = store ?: remember(context) { DictationStore(context) }
    if (!credentials.hasKey) return
    val microphone = recorder ?: remember(context) { M4aRecorder(context) }
    val client = transcriber ?: remember { GroqTranscriber() }
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onTranscript)
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var httpStatus by remember { mutableIntStateOf(0) }
    var retrySeconds by remember { mutableLongStateOf(0) }
    var audio by remember { mutableStateOf<File?>(null) }
    var upload by remember { mutableStateOf<Job?>(null) }
    var operation by remember { mutableIntStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(credentials.options()) }
    var prompt by remember { mutableStateOf("") }

    fun cancel() {
        operation++
        awaitingPermission = false
        upload?.cancel()
        upload = null
        microphone.cancel()
        audio?.delete()
        audio = null
        recording = false
        busy = false
    }
    fun start(granted: Boolean) {
        if (!alive || !awaitingPermission) return
        awaitingPermission = false
        if (!granted) { error = R.string.dictation_permission_denied; return }
        error = null
        try {
            microphone.start {
                cancel()
                error = R.string.dictation_too_large
            }
            recording = true
        } catch (_: Exception) { error = R.string.dictation_record_failed }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start(it) }
    fun transcribe() {
        val file = audio ?: return
        if (busy || retrySeconds > 0) return
        busy = true
        error = null
        val currentOperation = ++operation
        upload = scope.launch {
            try {
                val key = credentials.readKey() ?: throw DictationFailure.Unauthorized
                val result = withContext(Dispatchers.IO) { client.transcribe(file, key, options, prompt) }
                ensureActive()
                if (result.isBlank()) error = R.string.dictation_empty else callback(result)
                file.delete()
                audio = null
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (limited: DictationFailure.RateLimited) {
                retrySeconds = limited.seconds
                error = R.string.dictation_rate_limit
            } catch (failed: DictationFailure.Http) {
                httpStatus = failed.status
                error = R.string.dictation_http_failed
            } catch (_: DictationFailure.Unauthorized) { error = R.string.dictation_unauthorized
            } catch (_: DictationFailure.TooLarge) {
                file.delete(); audio = null
                error = R.string.dictation_too_large
            } catch (_: Exception) { error = R.string.dictation_failed
            } finally { if (operation == currentOperation) busy = false }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, microphone) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { alive = false; lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(retrySeconds) {
        if (retrySeconds > 0) { delay(1000); retrySeconds-- }
    }
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (recording) Text(stringResource(R.string.dictation_recording))
        if (busy) Text(stringResource(R.string.dictation_uploading))
        error?.let {
            Text(when (it) {
                R.string.dictation_rate_limit -> stringResource(it, retrySeconds)
                R.string.dictation_http_failed -> stringResource(it, httpStatus)
                else -> stringResource(it)
            }, color = MaterialTheme.colorScheme.error)
        }
        Row {
            when {
                recording -> TextButton(onClick = {
                    recording = false
                    try { audio = microphone.stop(); transcribe() }
                    catch (_: Exception) { microphone.cancel(); error = R.string.dictation_record_failed }
                }) { Text(stringResource(R.string.dictation_stop_button)) }
                !busy && audio == null -> TextButton(enabled = enabled && !awaitingPermission, onClick = {
                    options = credentials.options()
                    prompt = DictationPrompt.build(options.vocabulary, names, lastResponse, options.autoContext)
                    awaitingPermission = true
                    when {
                        requestPermission != null -> requestPermission(::start)
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> start(true)
                        else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Text(stringResource(R.string.dictation_start_button))
                }
                !busy && audio != null -> TextButton(enabled = enabled && retrySeconds == 0L, onClick = ::transcribe) {
                    Text(stringResource(R.string.dictation_retry_button))
                }
            }
            if (recording || busy || audio != null) TextButton(onClick = { cancel(); error = null }) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    }
}
