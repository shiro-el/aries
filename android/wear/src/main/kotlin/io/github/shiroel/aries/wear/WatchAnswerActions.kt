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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
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
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchCommand
import io.github.shiroel.aries.weardata.WatchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Explicit sends only. A timeout is uncertain and never retried automatically. */
@Composable
internal fun WatchAnswerActions(agent: WatchAgent, session: WatchDetailSession) {
    val view = LocalView.current
    val replyLabel = stringResource(R.string.wear_answer_reply)
    val sending = session.answer(agent).pending
    val result = session.answer(agent).result
    val check = session.check(agent)
    var draft by remember(agent.hostId, agent.terminal, agent.stateToken) { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        if (response.resultCode == Activity.RESULT_OK) {
            draft = response.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(REPLY_KEY)?.toString() }.orEmpty()
        }
    }
    fun send(command: WatchCommand, path: String) {
        session.send(command, path) { outcome ->
            view.performHapticFeedback(if (outcome == WatchResult.SENT) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
            if (outcome == WatchResult.SENT) draft = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!agent.contentHidden && result == null && !sending && check !in listOf(WatchDetailSession.DetailCheck.LOADING, WatchDetailSession.DetailCheck.FAILED, WatchDetailSession.DetailCheck.PHONE)) {
            if (agent.status == "blocked") {
                if (agent.canAnswerOnWatch && agent.requestId.isNotBlank()) {
                    agent.choices.forEachIndexed { index, choice ->
                        when {
                            agent.requestKind != "permission" -> ActionChip(choice) { send(WatchCommand(agent, choice = index), WatchCommand.ANSWER_PATH) }
                            choice == "Allow" -> HoldToAllow { send(WatchCommand(agent, choice = index), WatchCommand.ANSWER_PATH) }
                            choice == "Deny" -> ActionChip(stringResource(R.string.wear_answer_deny)) { send(WatchCommand(agent, choice = index), WatchCommand.ANSWER_PATH) }
                        }
                    }
                }
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
                    try { launcher.launch(intent) } catch (_: android.content.ActivityNotFoundException) { session.openPhoneRequired(agent) }
                }
            }
        }
        val message = when {
            sending -> stringResource(R.string.wear_answer_sending)
            result != null -> stringResource(when (result) {
                WatchResult.SENT -> R.string.wear_answer_sent
                WatchResult.CHANGED -> R.string.wear_answer_changed
                WatchResult.LOCKED, WatchResult.PHONE -> R.string.wear_answer_phone
                WatchResult.FAILED -> R.string.wear_answer_failed
                WatchResult.UNKNOWN -> R.string.wear_answer_unknown
            })
            check == WatchDetailSession.DetailCheck.LOADING -> stringResource(R.string.wear_detail_checking)
            check == WatchDetailSession.DetailCheck.FAILED -> stringResource(R.string.wear_detail_failed)
            check == WatchDetailSession.DetailCheck.PHONE -> stringResource(R.string.wear_answer_phone)
            else -> ""
        }
        // Keep one live node as progress becomes an outcome, even if the request changes.
        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = Color(if (result in listOf(WatchResult.FAILED, WatchResult.UNKNOWN, WatchResult.CHANGED)) WatchConsole.ERROR else WatchConsole.SAFFRON))
        if (check == WatchDetailSession.DetailCheck.FAILED || result == WatchResult.FAILED)
            ActionChip(stringResource(R.string.wear_detail_try_again)) { session.recover(agent) }

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
    var confirming by remember { mutableStateOf(false) }
    // A completed hold is 800 ms; releasing or scrolling before then cancels it.
    Column(Modifier.fillMaxWidth().background(Color(WatchConsole.CARD), WatchConsoleShape).semantics(mergeDescendants = true) {
        role = Role.Button
        contentDescription = label
        onClick { confirming = true; true }
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
    androidx.wear.compose.material.dialog.Dialog(showDialog = confirming, onDismissRequest = { confirming = false }) {
        androidx.wear.compose.foundation.lazy.ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState(initialCenterItemIndex = 0),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 32.dp), autoCentering = null,
        ) {
            item { Text(stringResource(R.string.wear_answer_confirm_allow)) }
            item { ActionChip(stringResource(R.string.wear_answer_allow)) { confirming = false; callback() } }
            item { ActionChip(stringResource(R.string.wear_interrupt_cancel)) { confirming = false } }
        }
    }

}
