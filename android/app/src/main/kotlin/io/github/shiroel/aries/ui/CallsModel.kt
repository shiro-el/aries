package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CallFeedState
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.CallListener
import io.github.shiroel.aries.core.CallReplyOutcome
import io.github.shiroel.aries.core.CallWatch
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One Call and the Host it came from. */
data class CallEntry(val host: Host, val call: CallInfo) {
    val key get() = "${host.id}/${call.id}"

    /** It can still be answered with a Choice from here. */
    val answerable get() = call.topLevel && call.terminal != null && call.answer == null && call.choices.isNotEmpty()
}

/** What the last answer from Call history ran into, beyond the recorded answer. */
sealed interface ReplyNote {
    /** The reply never started on the Host (nothing was typed); try again. */
    data class NotSent(val detail: String) : ReplyNote

    /** The Beacon answered with [outcome] (anything but a plain delivery). */
    data class Outcome(val outcome: CallReplyOutcome) : ReplyNote
}

/**
 * Every Host's Call history, kept live by one core watch per Host, and the
 * banner for a Call that arrives while the app is open.
 *
 * While the app is in front it watches the Hosts whose key opens without a
 * biometric prompt ([AriesCoreInterface.canAnswerInBackground]), so a
 * banner never asks for a fingerprint by itself; opening Call history
 * watches every Host.
 */
class CallsModel(
    private val core: AriesCoreInterface,
    private val scope: CoroutineScope,
    private val keyWork: CoroutineContext = EmptyCoroutineContext,
) {
    /** All Hosts' Calls, newest first. */
    var calls by mutableStateOf<List<CallEntry>>(emptyList())
        private set

    /** The newest Call that arrived while watching and asks for the user; `null` when dismissed. */
    var banner by mutableStateOf<CallEntry?>(null)
        private set

    /** Hosts whose feed is down, with why (shown in Call history). */
    var problems by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Calls being answered, by [CallEntry.key]. */
    var replying by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The last answer's note per Call, by [CallEntry.key]. */
    var replyNotes by mutableStateOf<Map<String, ReplyNote>>(emptyMap())
        private set

    /**
     * Answers [entry]'s Call with Choice [choice]: the Host's Beacon types it
     * into the calling Terminal once. The recorded answer shows on the Call
     * right away (the feed confirms it later).
     */
    fun reply(entry: CallEntry, choice: Int) {
        val key = entry.key
        if (key in replying) return
        replying = replying + key
        replyNotes = replyNotes - key
        scope.launch {
            val note: ReplyNote? = try {
                val r = withContext(keyWork) { core.replyCall(entry.host.id, entry.call.id, choice.toUInt()) }
                r.answer?.let { answer ->
                    perHost[entry.host.id] = perHost[entry.host.id].orEmpty().map {
                        if (it.id == entry.call.id) it.copy(answer = answer) else it
                    }
                    publish()
                }
                when (r.outcome) {
                    CallReplyOutcome.DELIVERED, CallReplyOutcome.FAILED, CallReplyOutcome.UNCONFIRMED -> null
                    else -> ReplyNote.Outcome(r.outcome)
                }
            } catch (e: CoreException) {
                ReplyNote.NotSent(e.message.orEmpty())
            }
            replying = replying - key
            replyNotes = if (note == null) replyNotes - key else replyNotes + (key to note)
        }
    }

    /** A Host's watch; [watch] is `null` while it is being started. */
    private class Slot {
        var watch: CallWatch? = null
    }

    private val watches = mutableMapOf<String, Slot>()
    private val perHost = mutableMapOf<String, List<CallInfo>>()
    private var hosts: Map<String, Host> = emptyMap()

    /**
     * Watches each of [hosts] not watched yet. With [everyHost] false, Hosts
     * that would need a biometric unlock are left out. Calls of Hosts no
     * longer in [hosts] are dropped.
     */
    fun watch(hosts: List<Host>, everyHost: Boolean) {
        this.hosts = hosts.associateBy { it.id }
        (watches.keys - this.hosts.keys).forEach { stop(it) }
        perHost.keys.retainAll(this.hosts.keys)
        publish()
        for (host in hosts) {
            if (host.id in watches) continue
            val allowed = everyHost || try {
                core.canAnswerInBackground(host.id)
            } catch (e: CoreException) {
                false
            }
            if (!allowed) continue
            val slot = Slot()
            watches[host.id] = slot
            scope.launch {
                try {
                    val w = withContext(keyWork) { core.watchCalls(host.id, Listener(host.id, slot)) }
                    if (watches[host.id] === slot) {
                        slot.watch = w
                    } else {
                        w.stop()
                        w.close()
                    }
                } catch (e: CoreException) {
                    if (watches[host.id] === slot) watches.remove(host.id)
                    problems = problems + (host.id to (e.message ?: ""))
                }
            }
        }
    }

    /** Stops every watch (the app went to the background). The last lists stay. */
    fun stopAll() {
        watches.keys.toList().forEach { stop(it) }
    }

    fun dismissBanner() {
        banner = null
    }

    private fun stop(hostId: String) {
        watches.remove(hostId)?.watch?.let {
            it.stop()
            it.close()
        }
    }

    private fun publish() {
        calls = perHost.flatMap { (id, list) -> hosts[id]?.let { h -> list.map { CallEntry(h, it) } }.orEmpty() }
            .sortedByDescending { it.call.atMs }
    }

    /** Core callbacks arrive on core threads; state changes happen on [scope]. */
    private inner class Listener(private val hostId: String, private val slot: Slot) : CallListener {
        /** Callbacks of a watch that was stopped (or replaced) are ignored. */
        private val current get() = watches[hostId] === slot

        override fun onCalls(calls: List<CallInfo>) {
            scope.launch {
                if (!current) return@launch
                perHost[hostId] = calls
                publish()
            }
        }

        override fun onNewCall(call: CallInfo) {
            scope.launch {
                val host = hosts[hostId]
                if (!current || host == null) return@launch
                // Helper Agents' and unconfirmed Calls are only recorded.
                if (call.pushesByDefault) banner = CallEntry(host, call)
            }
        }

        override fun onState(state: CallFeedState) {
            scope.launch {
                if (!current) return@launch
                problems = when (state) {
                    CallFeedState.Live -> problems - hostId
                    is CallFeedState.Reconnecting -> problems + (hostId to state.detail)
                }
            }
        }
    }
}
