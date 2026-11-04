package io.github.shiroel.aries.wear

import android.app.Activity
import android.app.RemoteInput
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Chip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchCommand
import io.github.shiroel.aries.weardata.WatchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Explicit sends only. A timeout is uncertain and never retried automatically. */
@Composable
fun WatchAnswerActions(agent: WatchAgent, onOpenPhone: (WatchAgent) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val replyLabel = stringResource(R.string.wear_answer_reply)
    val scope = rememberCoroutineScope()
    var sending by remember(agent.stateToken, agent.requestId) { mutableStateOf(false) }
    var result by remember(agent.stateToken, agent.requestId) { mutableStateOf<WatchResult?>(null) }
    var draft by remember(agent.hostId, agent.terminal, agent.stateToken) { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        if (response.resultCode == Activity.RESULT_OK) {
            draft = response.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(REPLY_KEY)?.toString() }.orEmpty()
        }
    }
    fun send(command: WatchCommand, path: String) {
        if (sending || result != null) return
        sending = true
        scope.launch {
            result = try {
                withTimeout(15_000) {
                    val store = WatchStateStore(context)
                    store.hydrate(context)
                    val phone = requireNotNull(store.phoneNode())
                    WatchResult.fromBytes(Wearable.getMessageClient(context).sendRequest(phone, path, command.toBytes()).await())
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                WatchResult.UNKNOWN
            } catch (_: Exception) { WatchResult.UNKNOWN }
            finally { sending = false }
            view.performHapticFeedback(if (result == WatchResult.SENT) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
            if (result == WatchResult.SENT) draft = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!agent.contentHidden && result == null && !sending) {
            if (agent.status == "blocked") {
                if (agent.canAnswerOnWatch && agent.requestId.isNotBlank()) {
                    agent.choices.forEachIndexed { index, choice ->
                        when {
                            agent.requestKind != "permission" -> ActionChip(choice) { send(WatchCommand(agent, choice = index), WatchCommand.ANSWER_PATH) }
                            choice == "Allow" -> HoldToAllow { send(WatchCommand(agent, choice = index), WatchCommand.ANSWER_PATH) }
                            choice == "Deny" -> ActionChip(stringResource(R.string.wear_answer_deny)) { send(WatchCommand(agent, choice = index), WatchCommand.ANSWER_PATH) }
                        }
                    }
                } else ActionChip(stringResource(R.string.wear_answer_phone)) { onOpenPhone(agent) }
            }
            if (agent.status in listOf("done", "error")) {
                if (draft.isNotBlank()) {
                    Text(draft, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                    ActionChip(stringResource(R.string.wear_answer_send)) { send(WatchCommand(agent, text = draft), WatchCommand.REPLY_PATH) }
                    ActionChip(stringResource(R.string.wear_answer_discard)) { draft = "" }
                }
                ActionChip(stringResource(R.string.wear_answer_reply)) {
                    val input = RemoteInput.Builder(REPLY_KEY).setLabel(replyLabel).build()
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
                    try { launcher.launch(intent) } catch (_: android.content.ActivityNotFoundException) { result = WatchResult.PHONE }
                }
            }
        }
        if (sending) Text(stringResource(R.string.wear_answer_sending))
        result?.let { outcome ->
            Text(stringResource(when (outcome) {
                WatchResult.SENT -> R.string.wear_answer_sent
                WatchResult.CHANGED -> R.string.wear_answer_changed
                WatchResult.LOCKED, WatchResult.PHONE -> R.string.wear_answer_phone
                WatchResult.FAILED -> R.string.wear_answer_failed
                WatchResult.UNKNOWN -> R.string.wear_answer_unknown
            }), color = Color(if (outcome == WatchResult.SENT) WatchConsole.SAFFRON else WatchConsole.ERROR))
            if (outcome in listOf(WatchResult.PHONE, WatchResult.LOCKED))
                ActionChip(stringResource(R.string.wear_answer_phone)) { onOpenPhone(agent) }
        }
    }
}

private const val REPLY_KEY = "watch_reply"

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Chip(label = { Text(label) }, onClick = onClick, colors = ChipDefaults.secondaryChipColors(),
        shape = WatchConsoleShape, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun HoldToAllow(onAllow: () -> Unit) {
    val view = LocalView.current
    val callback by rememberUpdatedState(onAllow)
    var progress by remember { mutableFloatStateOf(0f) }
    val label = stringResource(R.string.wear_answer_hold_allow)
    // A completed hold is 800 ms; releasing or scrolling before then cancels it.
    Column(Modifier.fillMaxWidth().background(Color(WatchConsole.CARD), WatchConsoleShape).semantics {
        customActions = listOf(CustomAccessibilityAction(label) { callback(); true })
    }.pointerInput(Unit) {
        detectTapGestures(onPress = {
            kotlinx.coroutines.coroutineScope {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val hold = launch {
                    repeat(20) { delay(40); progress = (it + 1) / 20f }
                    callback()
                }
                try { tryAwaitRelease() } finally { hold.cancel(); progress = 0f }
            }
        })
    }.padding(12.dp)) {
        Text(label, color = Color(WatchConsole.SAFFRON))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(WatchConsole.MUTED).copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Color(WatchConsole.SAFFRON)))
        }
    }
}
