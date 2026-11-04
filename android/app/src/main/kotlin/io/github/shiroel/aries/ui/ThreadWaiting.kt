package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*

private data class ThreadWaiting(val key: AgentKey, val name: String, val agent: AgentEntry?, val call: CallEntry?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadWaitingPill(model: HostsModel, chat: ChatModel) {
    val current = AgentKey(chat.host.id, chat.terminal.address)
    val otherAgents = model.drawerAgents.filter { it.key != current }
    val agents = otherAgents.filter { it.terminal.agentStatus is AgentStatus.Blocked }
    val calls = model.calls.calls.filter { it.live && it.call.topLevel && it.call.terminal != null &&
        it.call.answer == null && it.call.urgency != CallUrgency.RECORD &&
        AgentKey(it.host.id, it.call.terminal!!) != current }
    val waiting = (calls.map { call ->
        val key = AgentKey(call.host.id, call.call.terminal!!)
        val agent = otherAgents.firstOrNull { it.key == key }
        ThreadWaiting(key, agent?.terminal?.let(::displayName) ?: call.call.terminal!!, agent, call)
    } + agents.filter { agent -> calls.none { it.host.id == agent.host.id && it.call.terminal == agent.terminal.address } }
        .map { ThreadWaiting(it.key, displayName(it.terminal), it, null) })
        .sortedWith(compareByDescending<ThreadWaiting> { it.call?.call?.urgency?.ordinal ?: -1 }
            .thenBy { it.call?.call?.atMs?.toLong() ?: it.agent?.terminal?.statusMs?.toLong() ?: Long.MAX_VALUE })
        .distinctBy { it.key }
    var sheet by remember(chat) { mutableStateOf(false) }
    var selected by remember(chat) { mutableStateOf<AgentKey?>(null) }
    // Keep the selected identity after an answer; a disappearing queue entry cannot switch under a tap.
    var held by remember(chat) { mutableStateOf<ThreadWaiting?>(null) }
    if (waiting.isNotEmpty()) {
        val urgent = waiting.first().call?.call?.urgency in setOf(CallUrgency.URGENT, CallUrgency.EMERGENCY)
        val text = if (waiting.size == 1) stringResource(if (urgent) R.string.thread_waiting_urgent_single else R.string.thread_waiting_single,
            waiting.first().name) else pluralStringResource(if (urgent) R.plurals.thread_waiting_urgent else R.plurals.thread_waiting,
            waiting.size - 1, waiting.first().name, waiting.size - 1)
        FilledTonalButton(onClick = {
            selected = waiting.first().key; held = waiting.first(); sheet = true
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("thread-waiting-pill"),
            shape = RoundedCornerShape(10.dp)) { Text(text) }
    }
    if (sheet) {
        val target = waiting.firstOrNull { it.key == selected } ?: held
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            if (target != null) {
                val index = waiting.indexOfFirst { it.key == target.key }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(target.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val previousLabel = stringResource(R.string.thread_previous_waiting)
                    val nextLabel = stringResource(R.string.thread_next_waiting_button)
                    TextButton(onClick = {
                        val next = waiting[(if (index <= 0) waiting.lastIndex else index - 1).coerceAtLeast(0)]
                        selected = next.key; held = next
                    }, enabled = waiting.size > 1, modifier = Modifier.semantics { contentDescription = previousLabel }) { Text(stringResource(R.string.thread_previous)) }
                    Text(stringResource(R.string.thread_waiting_position, (index + 1).coerceAtLeast(1), waiting.size.coerceAtLeast(1)),
                        style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = {
                        val next = waiting[(index + 1).coerceAtLeast(0) % waiting.size]
                        selected = next.key; held = next
                    }, enabled = waiting.size > 1, modifier = Modifier.semantics { contentDescription = nextLabel }) { Text(stringResource(R.string.thread_next)) }
                }
                TextButton(onClick = {
                    sheet = false
                    model.releaseWaitingChat(target.key)
                    model.openPushedAgent(target.key.hostId, target.key.address, openChat = true)
                }) { Text(stringResource(R.string.thread_open_conversation)) }
                if (target.call != null) {
                    val live = model.calls.calls.firstOrNull { it.key == target.call.key } ?: target.call.copy(live = false)
                    ThreadCallPanel(live, model.calls)
                } else if (target.agent != null) ThreadWaitingQuestion(model, target.agent)
                if (index < 0 && waiting.isNotEmpty()) TextButton(onClick = {
                    selected = waiting.first().key; held = waiting.first()
                }) { Text(stringResource(R.string.thread_next_waiting, waiting.first().name)) }
            } else Text(stringResource(R.string.thread_waiting_empty), Modifier.padding(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun ThreadWaitingQuestion(model: HostsModel, agent: AgentEntry) {
    val preview = remember(agent.key) { model.previewWaitingChat(agent) }
    DisposableEffect(preview) {
        if (preview != null) model.startTaskQuestion(preview)
        onDispose { if (preview != null && !model.isViewedChat(preview)) preview.stop() }
    }
    LifecycleStartEffect(preview) {
        preview?.resume()
        onStopOrDispose { if (preview != null && !model.isViewedChat(preview)) preview.pause() }
    }
    if (preview == null) {
        Text(stringResource(R.string.thread_waiting_open_required), Modifier.padding(16.dp))
        return
    }
    val question = threadAnswerQuestion(preview)
    Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
        val excerpt = preview.snapshot?.entries?.lastOrNull { it.body is ChatEntryBody.Agent }?.body as? ChatEntryBody.Agent
        excerpt?.let { Text(it.text, Modifier.padding(16.dp), maxLines = 4, style = MaterialTheme.typography.bodySmall) }
        ThreadConnectionGuidance(preview)
        if (question != null) ThreadAnswerPanel(preview, question)
        else Text(stringResource(if (preview.snapshot == null) R.string.chat_loading else R.string.thread_waiting_open_required), Modifier.padding(16.dp))
    }
}
