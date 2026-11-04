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
    val agents = otherAgents.filter { it.live && isWaiting(it.terminal.agentStatus) }
    val calls = model.calls.calls.filter { model.calls.isThreadWaiting(it) && it.call.terminal != null &&
        AgentKey(it.host.id, it.call.terminal!!) != current }
    val pending = (calls.map { call ->
        val key = AgentKey(call.host.id, call.call.terminal!!)
        val agent = otherAgents.firstOrNull { it.key == key }
        ThreadWaiting(key, agent?.let(model::threadTitle) ?: model.callCallerName(call) ?: stringResource(R.string.call_caller_unknown), agent, call)
    } + agents.filter { agent -> calls.none { it.host.id == agent.host.id && it.call.terminal == agent.terminal.address } }
        .map { ThreadWaiting(it.key, model.threadTitle(it), it, null) })
        .sortedWith(compareByDescending<ThreadWaiting> { it.call?.call?.urgency?.ordinal ?: -1 }
            .thenBy { it.call?.call?.atMs?.toLong() ?: it.agent?.terminal?.statusMs?.toLong() ?: Long.MAX_VALUE })
        .distinctBy { it.key }
    var sheet by chat::waitingSheetOpen
    var selected by chat::waitingSheetTarget
    // Keep the selected local Call even after its receipt removes it from pending.
    // A new Agent must never inherit the Choice button the user just tapped.
    var retained by remember(chat) { mutableStateOf<ThreadWaiting?>(null) }
    val held = retained?.takeIf { sheet && it.key == selected }?.let { target ->
        target.call?.let { call ->
            model.calls.calls.firstOrNull { it.key == call.key && !it.acknowledged && it.key in model.calls.localReplies }
                ?.let { target.copy(call = it) }
        }
    }
    val waiting = if (held == null) pending else pending.map { if (it.key == held.key) held else it }
        .let { if (it.any { entry -> entry.key == held.key }) it else it + held }
    if (pending.isNotEmpty()) {
        val urgent = waiting.first().call?.call?.urgency in setOf(CallUrgency.URGENT, CallUrgency.EMERGENCY)
        val text = if (waiting.size == 1) stringResource(if (urgent) R.string.thread_waiting_urgent_single else R.string.thread_waiting_single,
            waiting.first().name) else pluralStringResource(if (urgent) R.plurals.thread_waiting_urgent else R.plurals.thread_waiting,
            waiting.size - 1, waiting.first().name, waiting.size - 1)
        FilledTonalButton(onClick = {
            retained = pending.first(); selected = pending.first().key; sheet = true
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("thread-waiting-pill")
            .presentsCalls(calls.map { it.key }.toSet()),
            shape = RoundedCornerShape(10.dp)) { Text(text) }
    }
    if (sheet) {
        val target = waiting.firstOrNull { it.key == selected } ?: waiting.firstOrNull()
        SideEffect { selected = target?.key; retained = target }
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            if (target != null) {
                val index = waiting.indexOfFirst { it.key == target.key }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    QueueHeading(listOf(stringResource(R.string.thread_waiting_position, index + 1, waiting.size), target.name).joinToString(" · "),
                        "${target.key.hostId}:${target.key.address}", Modifier.weight(1f))
                    val previousLabel = stringResource(R.string.thread_previous_waiting)
                    val nextLabel = stringResource(R.string.thread_next_waiting_button)
                    TextButton(onClick = {
                        val next = waiting[(if (index <= 0) waiting.lastIndex else index - 1).coerceAtLeast(0)]
                        retained = next; selected = next.key
                    }, enabled = waiting.size > 1, modifier = Modifier.semantics { contentDescription = previousLabel }) { Text(stringResource(R.string.thread_previous)) }
                    TextButton(onClick = {
                        val next = waiting[(index + 1).coerceAtLeast(0) % waiting.size]
                        retained = next; selected = next.key
                    }, enabled = waiting.size > 1, modifier = Modifier.semantics { contentDescription = nextLabel }) { Text(stringResource(R.string.thread_next)) }
                }
                TextButton(onClick = {
                    sheet = false
                    model.releaseWaitingChat(target.key)
                    model.openPushedAgent(target.key.hostId, target.key.address, openChat = true)
                }) { Text(stringResource(R.string.thread_open_conversation)) }
                if (target.call != null) {
                    val live = model.calls.calls.firstOrNull { it.key == target.call.key } ?: target.call.copy(live = false)
                    ThreadCallPanel(live, model.calls, onDismiss = { model.calls.closeThreadCall(live) }, announce = true, onOpenTerminal = {
                        sheet = false
                        model.openPushedAgent(live.host.id, live.call.terminal!!)
                    })
                } else if (target.agent != null) ThreadWaitingQuestion(model, target.agent)
            } else Text(stringResource(R.string.thread_waiting_empty), Modifier.padding(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun ThreadWaitingQuestion(model: HostsModel, agent: AgentEntry) {
    if (agent.terminal.agentStatus is AgentStatus.Error) {
        Text(stringResource(R.string.agents_waiting_error), Modifier.padding(16.dp))
        statusMessage(agent.terminal.agentStatus)?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        }
        return
    }
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
        if (question != null) ThreadAnswerPanel(preview, question, announce = true)
        else Text(stringResource(if (preview.snapshot == null) R.string.chat_loading else R.string.thread_waiting_open_required), Modifier.padding(16.dp))
    }
}
