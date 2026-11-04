package io.github.shiroel.aries.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.CoreException
import java.text.DateFormat
import java.util.Date

/** Waiting Calls first, then recorded history; reply state stays owned by CallsModel. */
@Composable
internal fun CallInboxPanel(
    model: CallsModel,
    onChat: (CallEntry) -> Unit,
    modifier: Modifier = Modifier,
    callerName: (CallEntry) -> String? = { null },
    onInstallBeacon: (Host) -> Unit,
    onFeedSettings: (Host) -> Unit,
    openingCall: String? = null,
    blockedAgents: Int = 0,
    onWaitingAgents: () -> Unit = {},
) {
    val waiting = model.calls.filter { it.answerable }.let { calls ->
        if (LocalLayoutTokens.current == tokensFor(AppLayout.TASKS))
            calls.sortedWith(compareByDescending<CallEntry> { it.call.urgency.ordinal }.thenByDescending { it.call.atMs })
        else calls
    }
    val other = model.calls.filter { !it.answerable && it.call.answer == null }
    val past = model.calls.filter { it.call.answer != null }
    LazyColumn(modifier.fillMaxWidth().testTag("call-inbox-list")) {
        items(model.problems.toList(), key = { "problem-${it.first}" }) { (hostId, detail) ->
            val host = model.problemHost(hostId) ?: return@items
            val missingHost = model.missingBeaconHosts[hostId]
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                if (missingHost != null) Text(
                    stringResource(R.string.call_inbox_install_beacon, host.name),
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onInstallBeacon(host) }.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                ) else {
                    Text(stringResource(R.string.calls_feed_down, host.name, detail),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    val failure = model.startFailures[hostId]
                    val guidance = when (failure) {
                        is CoreException.NoKey -> R.string.connect_no_key
                        is CoreException.KeyLocked -> R.string.key_locked_body
                        is CoreException.KeyInvalidated -> R.string.key_invalidated_body
                        is CoreException.BiometricUnavailable -> R.string.key_biometric_unavailable_title
                        else -> null
                    }
                    guidance?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    if (model.isRetrying(hostId)) Text(stringResource(R.string.call_feed_retrying), style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (failure !is CoreException.NoKey && failure !is CoreException.KeyInvalidated && failure !is CoreException.BiometricUnavailable) {
                            TextButton(onClick = { model.retry(hostId) }, modifier = Modifier.testTag("call-feed-retry:$hostId")) {
                                Text(stringResource(if (failure is CoreException.KeyLocked) R.string.call_feed_unlock else R.string.call_feed_retry))
                            }
                        }
                        TextButton(onClick = { onFeedSettings(host) }) { Text(stringResource(R.string.call_feed_settings)) }
                    }
                }
            }
        }
        if (blockedAgents > 0) item {
            TextButton(onClick = onWaitingAgents, modifier = Modifier.testTag("call-inbox-waiting-agents")) {
                Text(stringResource(R.string.call_inbox_blocked, blockedAgents))
            }
        }
        if (model.syncingHosts.isNotEmpty()) item {
            Text(pluralStringResource(R.plurals.call_inbox_syncing, model.syncingHosts.size, model.syncingHosts.size), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (waiting.isEmpty() && model.syncingHosts.isEmpty() && model.problems.isEmpty()) item {
            Text(stringResource(R.string.call_inbox_empty), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        items(waiting, key = { it.key }) { entry ->
            CallInboxRow(model, entry, onChat, callerName(entry), openingCall == entry.key)
        }
        if (other.isNotEmpty()) item {
            Text(stringResource(R.string.call_inbox_other), Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
        }
        items(other, key = { it.key }) { entry ->
            CallInboxRow(model, entry, onChat, callerName(entry), openingCall == entry.key)
        }
        if (past.isNotEmpty()) item {
            Text(stringResource(R.string.call_inbox_past), Modifier.testTag("call-inbox-past").padding(12.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
        }
        items(past, key = { it.key }) { entry ->
            CallInboxRow(model, entry, onChat, callerName(entry), openingCall == entry.key)
        }
    }
}

@Composable
private fun CallInboxRow(model: CallsModel, entry: CallEntry, onChat: (CallEntry) -> Unit, callerName: String?, opening: Boolean) {
    CallRow(entry, entry.key in model.replying, model.replyNotes[entry.key],
        onChoice = { model.reply(entry, it) }, onChat = { onChat(entry) }, callerName = callerName, opening = opening)
    HorizontalDivider()
}

@Composable
private fun CallRow(
    entry: CallEntry,
    sending: Boolean,
    note: ReplyNote?,
    onChoice: (Int) -> Unit,
    onChat: () -> Unit,
    callerName: String?,
    opening: Boolean,
) {
    val call = entry.call
    val tasks = LocalLayoutTokens.current == tokensFor(AppLayout.TASKS)
    val urgent = tasks && entry.answerable && (call.urgency == CallUrgency.URGENT || call.urgency == CallUrgency.EMERGENCY)
    Column(Modifier.fillMaxWidth().testTag("call-${call.id}")
        .then(if (urgent) Modifier.background(Brand.Saffron.copy(alpha = .07f)).drawBehind {
            drawLine(Brand.Saffron, Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), 3.dp.toPx())
        } else Modifier).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (urgent) androidx.compose.material3.Surface(color = Brand.Saffron, contentColor = Brand.Ink,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
            Text(stringResource(R.string.tasks_urgent_call), Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall)
        }
        Text(call.title, style = MaterialTheme.typography.titleMedium)
        val caller = call.terminal?.let { stringResource(R.string.call_inbox_caller, callerName ?: stringResource(R.string.call_caller_unknown), entry.host.name) }
            ?: entry.host.name
        Text(
            stringResource(R.string.call_meta, urgencyLabel(call.urgency), caller, formatTime(call.atMs)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (call.body.isNotEmpty()) Text(call.body, style = MaterialTheme.typography.bodyMedium)
        if (!entry.live) {
            entry.checkedAtMs?.let { checked ->
                Text(stringResource(R.string.call_inbox_read_only, formatTime(checked.toULong())),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (call.answer == null) {
            val reason = when {
                call.terminal == null -> R.string.call_unconfirmed
                !call.topLevel -> R.string.call_inbox_helper
                else -> null
            }
            reason?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
        if (call.choices.isNotEmpty() && !entry.answerable && !sending) {
            Text(stringResource(R.string.call_choices, call.choices.joinToString(" · ")), style = MaterialTheme.typography.bodySmall)
        }
        call.answer?.let { a ->
            AnnouncedText(stringResource(a.state.labelResource(), a.text), style = MaterialTheme.typography.bodySmall)
            if (a.state != CallAnswerState.DELIVERED) {
                a.detail?.let { AnnouncedText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        when {
            sending -> AnnouncedText(stringResource(R.string.call_answer_sending), style = MaterialTheme.typography.bodySmall)
            entry.answerable -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                call.choices.forEachIndexed { i, choice ->
                    ConsoleChatChoice(i + 1, choice,
                        accessibilityLabel = stringResource(R.string.call_answer_choice_label, call.title, choice),
                        onClick = { onChoice(i) })
                }
            }
        }
        note?.let { replyNoteText(it) }?.let { text ->
            AnnouncedText(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        call.terminal?.let {
            val label = stringResource(R.string.call_open_chat_label, callerName ?: stringResource(R.string.call_caller_unknown), call.title)
            TextButton(onClick = onChat, enabled = !opening, modifier = Modifier.semantics { contentDescription = label }) {
                Text(stringResource(if (opening) R.string.reconnecting else R.string.call_open_chat))
            }
        }
    }
}

@Composable
internal fun urgencyLabel(u: CallUrgency): String = stringResource(
    when (u) {
        CallUrgency.RECORD -> R.string.call_urgency_record
        CallUrgency.CHECK -> R.string.call_urgency_check
        CallUrgency.URGENT -> R.string.call_urgency_urgent
        CallUrgency.EMERGENCY -> R.string.call_urgency_emergency
    },
)

private fun formatTime(ms: ULong): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms.toLong()))

