package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Host-owned delivery scheduler. A Chat screen only edits/displays this same FIFO.
 * Transcript receipts can disappear before Working arrives, so turn fencing lives
 * separately from bubbles and survives navigation and watch replacement.
 */
class HostChatQueue {
    var messages by mutableStateOf<List<QueuedChatMessage>>(emptyList())
        internal set
    var sending by mutableStateOf<ChatModel.Sending?>(null)
        internal set
    var confirmingStop by mutableStateOf(false)
        private set
    private var waitingForTurn by mutableStateOf(false)
    private var attemptedMessage: String? = null
    private var sawWorking = false
    private var dispatchTurn: ULong? = null
    private var scheduler: Job? = null

    val waitingCount: Int get() = messages.count { it.phase == QueuedMessagePhase.Waiting }
    val uncertainBarrier: Boolean get() = messages.any { it.phase == QueuedMessagePhase.Uncertain }

    internal fun confirmStop(open: Boolean) { confirmingStop = open }

    internal fun attempted(messageId: String, terminal: TerminalInfo) {
        attemptedMessage = messageId
        dispatchTurn = terminal.turn
        sawWorking = false
        waitingForTurn = true
    }

    internal fun rejected(messageId: String) {
        if (attemptedMessage == messageId) waitingForTurn = false
    }

    internal fun update(terminal: TerminalInfo) {
        if (!waitingForTurn) return
        if (terminal.agentStatus == AgentStatus.Working) sawWorking = true
        // turn increments only when Working ends, including a transition missed
        // during a reconnect. An unchanged Idle snapshot is never sufficient.
        if (terminal.agentStatus != AgentStatus.Working &&
            (sawWorking || dispatchTurn?.let { terminal.turn > it } == true)) waitingForTurn = false
    }

    internal fun restoreTurnFence() {
        messages.lastOrNull { it.confirmed && it.dispatchTurn != null }?.let {
            attemptedMessage = it.id
            dispatchTurn = it.dispatchTurn
            waitingForTurn = true
        }
    }

    internal fun start(scope: CoroutineScope, eligible: () -> Boolean, dispatch: (QueuedChatMessage) -> Unit) {
        if (scheduler?.isActive == true) return
        scheduler = scope.launch {
            snapshotFlow {
                if (!confirmingStop && !waitingForTurn && sending == null && !uncertainBarrier && eligible())
                    messages.firstOrNull { it.phase == QueuedMessagePhase.Waiting }
                else null
            }.collect { message -> if (message != null) dispatch(message) }
        }
    }

    internal fun stop() {
        confirmingStop = false
        scheduler?.cancel()
        scheduler = null
    }
}
