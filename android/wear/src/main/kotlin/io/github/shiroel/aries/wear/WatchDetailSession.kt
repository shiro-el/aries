package io.github.shiroel.aries.wear

import androidx.compose.runtime.*
import io.github.shiroel.aries.weardata.*
import kotlinx.coroutines.*

/** Activity-owned work and outcomes: leaving a detail never cancels a mutation. */
internal class WatchDetailSession(
    private val scope: CoroutineScope,
    private val request: suspend (String, ByteArray) -> ByteArray,
    private val onStopped: (WatchAgent, WatchInterruptResult) -> Unit = { _, _ -> },
) {
    private data class DetailKey(val host: String, val terminal: String, val token: String, val request: String)
    private fun WatchAgent.detailKey() = DetailKey(hostId, terminal, stateToken, requestId)
    private fun WatchAgent.key() = hostId to terminal
    private val live = mutableMapOf<DetailKey, WatchAgent>()
    private val inflight = mutableMapOf<DetailKey, Any>()
    private val checks = mutableStateMapOf<DetailKey, DetailCheck>()
    // Only an in-flight mutation spans turns, to prevent a second send until it finishes.
    private val pendingAnswers = mutableStateMapOf<Pair<String, String>, Boolean>()
    private val answers = mutableStateMapOf<DetailKey, WatchResult>()
    private val drafts = mutableStateMapOf<DetailKey, String>()
    private val stops = mutableStateMapOf<Pair<String, String>, Boolean>()
    var summary by mutableStateOf(WatchSummary.EMPTY)
        private set

    enum class DetailCheck { LOADING, READY, FAILED, PHONE }
    data class AnswerState(val pending: Boolean = false, val result: WatchResult? = null)
    fun check(agent: WatchAgent) = checks[agent.detailKey()]
    fun answer(agent: WatchAgent) = AnswerState(
        pending = pendingAnswers[agent.key()] == true,
        result = answers[agent.detailKey()],
    )
    fun draft(agent: WatchAgent) = drafts[agent.detailKey()].orEmpty()
    fun setDraft(agent: WatchAgent, text: String) {
        if (agent.contentHidden) return
        if (text.isEmpty()) drafts.remove(agent.detailKey()) else drafts[agent.detailKey()] = text
    }
    fun stopping(agent: WatchAgent) = stops[agent.key()] == true

    /** Used for both onResume hydration and DataItem publications. Privacy always wins. */
    fun merge(incoming: WatchSummary) {
        val rows = incoming.inbox.map { row ->
            // Permission bodies are intentionally absent from phone cache publications.
            // A concrete request ID, however, is authoritative even within the same turn.
            if (!row.contentHidden && row.requestKind == "permission" && row.requestId.isBlank())
                live.values.firstOrNull { it.key() == row.key() && it.stateToken == row.stateToken } ?: row
            else row
        }
        val valid = rows.filter { !it.contentHidden }.map { it.detailKey() }.toSet()
        answers.keys.retainAll(valid)
        drafts.keys.retainAll(valid)
        live.keys.retainAll(valid)
        checks.keys.retainAll(valid)
        inflight.keys.retainAll(valid)
        summary = incoming.copy(inbox = rows.map { row ->
            if (row.contentHidden) row else live[row.detailKey()] ?: row
        })
    }

    fun load(agent: WatchAgent, force: Boolean = false) {
        if (agent.contentHidden || checks[agent.detailKey()] == DetailCheck.LOADING) return
        if (!force && checks[agent.detailKey()] == DetailCheck.READY) return
        val key = agent.detailKey()
        checks[key] = DetailCheck.LOADING
        val ticket = Any()
        inflight[key] = ticket
        scope.launch {
            val detail = try {
                withTimeout(50_000) {
                    val bytes = request(WatchCommand.DETAILS_PATH, OpenWatchAgent(agent.hostId, agent.terminal).toBytes())
                    if (bytes.isEmpty()) { if (inflight[key] === ticket) checks[key] = DetailCheck.PHONE; null }
                    else requireNotNull(WatchCommand.fromBytes(bytes)).agent
                }
            } catch (_: TimeoutCancellationException) { if (inflight[key] === ticket) checks[key] = DetailCheck.FAILED; null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (inflight[key] === ticket) checks[key] = DetailCheck.FAILED; null }
            if (inflight[key] !== ticket) return@launch
            inflight.remove(key)
            val current = summary.inbox.firstOrNull { it.key() == agent.key() }
            // A late reply cannot revive a hidden or replaced request.
            if (current?.detailKey() != key || current.contentHidden) return@launch
            if (detail == null) return@launch
            if (detail.key() != agent.key() || detail.contentHidden) { checks[key] = DetailCheck.FAILED; return@launch }
            // A body-free permission publication must find only the latest live menu.
            live.keys.removeAll { it.host == detail.hostId && it.terminal == detail.terminal }
            live[detail.detailKey()] = detail
            checks.remove(key)
            checks[detail.detailKey()] = DetailCheck.READY
            summary = summary.copy(inbox = summary.inbox.map { if (it.key() == agent.key()) detail else it })
        }
    }

    /** Recovery always rechecks live identity before enabling another explicit send. */
    fun recover(agent: WatchAgent) {
        if (answer(agent).pending) return
        answers.remove(agent.detailKey())
        load(agent, force = true)
    }

    fun openPhoneRequired(agent: WatchAgent) { answers[agent.detailKey()] = WatchResult.PHONE }

    fun send(command: WatchCommand, path: String, onResult: (WatchResult) -> Unit = {}) {
        val key = command.agent.key()
        if (answer(command.agent).let { it.pending || it.result != null }) return
        if (command.text.isNotBlank()) setDraft(command.agent, command.text)
        pendingAnswers[key] = true
        scope.launch {
            val result = try {
                withTimeout(65_000) { WatchResult.fromBytes(request(path, command.toBytes())) }
            } catch (_: TimeoutCancellationException) { WatchResult.UNKNOWN }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { WatchResult.UNKNOWN }
            pendingAnswers.remove(key)
            answers[command.agent.detailKey()] = result
            if (result == WatchResult.SENT) drafts.remove(command.agent.detailKey())
            onResult(result)
        }
    }

    fun stop(agent: WatchAgent) {
        val key = agent.key()
        if (stops[key] == true) return
        stops[key] = true
        scope.launch {
            val result = try {
                withTimeout(30_000) {
                    WatchInterruptResult.fromBytes(request(WatchInterrupt.PATH, OpenWatchAgent(agent.hostId, agent.terminal).toBytes()))
                        ?: WatchInterruptResult.UNKNOWN
                }
            } catch (_: TimeoutCancellationException) { WatchInterruptResult.UNKNOWN }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { WatchInterruptResult.UNKNOWN }
            stops[key] = false
            onStopped(agent, result)
        }
    }
}

internal fun WatchInterruptResult.messageResource() = when (this) {
    WatchInterruptResult.STOPPED -> R.string.wear_interrupt_stopped
    WatchInterruptResult.EXITED -> R.string.wear_interrupt_exited
    WatchInterruptResult.STILL_WORKING -> R.string.wear_interrupt_still_working
    WatchInterruptResult.NOT_WORKING -> R.string.wear_interrupt_not_working
    WatchInterruptResult.OPEN_PHONE -> R.string.wear_interrupt_open_phone
    WatchInterruptResult.BUSY -> R.string.wear_interrupt_busy
    WatchInterruptResult.UNKNOWN -> R.string.wear_interrupt_unknown
}
