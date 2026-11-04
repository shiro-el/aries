package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CallAnswer
import io.github.shiroel.aries.core.CallAnswerState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One Call and the Host it came from. */
data class CallEntry(val host: Host, val call: CallInfo, val live: Boolean = true, val checkedAtMs: Long? = null) {
    val key get() = "${host.id}/${call.id}"

    /** It can still be answered with a Choice from here. */
    val answerable get() = live && call.topLevel && call.terminal != null && call.answer == null && call.choices.isNotEmpty()
}

/** Feedback from a local reply, kept even when the Call is no longer answerable. */
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

    /** Watched Hosts still waiting for their first complete snapshot. */
    var syncingHosts by mutableStateOf<Set<String>>(emptySet())
        private set

    private var feedProblems by mutableStateOf<Map<String, CallFeedState>>(emptyMap())

    /** Startup failures are separate from a running core watch's automatic reconnect. */
    var startFailures by mutableStateOf<Map<String, CoreException>>(emptyMap())
        private set

    fun problemHost(hostId: String): Host? = hosts[hostId]
    fun isRetrying(hostId: String): Boolean = hostId in watches

    /** Hosts whose feed is down, with diagnostics for display only. */
    val problems: Map<String, String>
        get() = feedProblems.mapValues { (_, state) ->
            when (state) {
                is CallFeedState.BeaconNotInstalled -> state.detail
                is CallFeedState.Reconnecting -> state.detail
                CallFeedState.Live -> ""
            }
        }

    /** Hosts whose feed explicitly reports a missing Beacon. */
    val missingBeaconHosts: Map<String, Host>
        get() = feedProblems.filterValues { it is CallFeedState.BeaconNotInstalled }
            .keys.mapNotNull { hosts[it] }.associateBy { it.id }

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
        if (key in replying || calls.none { it.key == key && it.answerable }) return
        replying = replying + key
        replyNotes = replyNotes - key
        scope.launch {
            val note: ReplyNote? = try {
                val r = withContext(keyWork) { core.replyCall(entry.host.id, entry.call.id, choice.toUInt()) }
                r.answer?.let { answer ->
                    perHost[entry.host.id] = perHost[entry.host.id].orEmpty().map {
                        if (it.id == entry.call.id) it.copy(answer = mergeAnswer(it.answer, answer)) else it
                    }
                    publish()
                }
                when (r.outcome) {
                    CallReplyOutcome.DELIVERED -> null
                    else -> ReplyNote.Outcome(r.outcome)
                }
            } catch (e: CoreException) {
                ReplyNote.NotSent(e.message.orEmpty())
            }
            replying = replying - key
            replyNotes = if (note == null || key in confirmedFinals || calls.none { it.key == key }) replyNotes - key
                else replyNotes + (key to note)
        }
    }

    /** A Host's watch; [watch] is `null` while it is being started. */
    private class Slot {
        var watch: CallWatch? = null
    }

    private val watches = mutableMapOf<String, Slot>()
    private val confirmedFinals = mutableSetOf<String>()
    private val liveHosts = mutableSetOf<String>()
    private val checkedAt = mutableMapOf<String, Long>()
    private val perHost = mutableMapOf<String, List<CallInfo>>()
    private var hosts: Map<String, Host> = emptyMap()

    /**
     * Watches each of [hosts] not watched yet. With [everyHost] false, Hosts
     * that would need a biometric unlock are left out. Calls of Hosts no
     * longer in [hosts] are dropped.
     */
    fun watch(hosts: List<Host>, everyHost: Boolean) {
        retainHosts(hosts)
        for (host in hosts) {
            val allowed = everyHost || backgroundAllowed(host.id)
            if (!allowed) { stop(host.id); continue }
            if (host.id !in watches) start(host)
        }
    }

    /** Reconcile saved Hosts even while the app is not watching Calls. */
    fun retainHosts(hosts: List<Host>) {
        this.hosts = hosts.associateBy { it.id }
        (watches.keys - this.hosts.keys).forEach { stop(it) }
        perHost.keys.retainAll(this.hosts.keys)
        checkedAt.keys.retainAll(this.hosts.keys)
        confirmedFinals.retainAll(perHost.flatMap { (id, calls) -> calls.map { "$id/${it.id}" } }.toSet())
        feedProblems = feedProblems.filterKeys { it in this.hosts }
        startFailures = startFailures.filterKeys { it in this.hosts }
        replyNotes = replyNotes.filterKeys { key -> this.hosts.keys.any { key.startsWith("$it/") } }
        publish()
    }

    /** An explicit retry may unlock the key, unlike foreground background-eligible watching. */
    fun retry(hostId: String) {
        val host = hosts[hostId] ?: return
        stop(hostId)
        start(host)
    }

    private fun backgroundAllowed(hostId: String) = try {
        core.canAnswerInBackground(hostId)
    } catch (_: CoreException) { false }

    private fun start(host: Host) {
        val slot = Slot()
        watches[host.id] = slot
        syncingHosts = syncingHosts + host.id
        scope.launch {
            var backoff = 1_000L
            while (watches[host.id] === slot) {
                try {
                    val w = withContext(keyWork) { core.watchCalls(host.id, Listener(host.id, slot)) }
                    if (watches[host.id] === slot) slot.watch = w else {
                        w.stop()
                        w.close()
                    }
                    return@launch
                } catch (e: CoreException) {
                    // A stopped/replaced watch cannot restore a problem for a removed Host.
                    if (watches[host.id] !== slot) return@launch
                    syncingHosts = syncingHosts - host.id
                    startFailures = startFailures + (host.id to e)
                    feedProblems = feedProblems + (host.id to CallFeedState.Reconnecting(e.message.orEmpty()))
                    val retry = (e is CoreException.Unreachable || e is CoreException.Closed) && backgroundAllowed(host.id)
                    if (!retry) { watches.remove(host.id); return@launch }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    /** Stops every watch (the app went to the background). The last lists stay. */
    fun stopAll() {
        (watches.keys + feedProblems.keys).toList().forEach { stop(it) }
    }

    fun dismissReplyNote(key: String) {
        replyNotes = replyNotes - key
    }

    fun dismissBanner() {
        banner = null
    }

    private fun stop(hostId: String) {
        syncingHosts = syncingHosts - hostId
        liveHosts.remove(hostId)
        feedProblems = feedProblems - hostId
        startFailures = startFailures - hostId
        if (banner?.host?.id == hostId) banner = null
        watches.remove(hostId)?.watch?.let {
            it.stop()
            it.close()
        }
        publish()
    }

    private fun publish() {
        calls = perHost.flatMap { (id, list) -> hosts[id]?.let { h -> list.map { CallEntry(h, it, id in liveHosts, checkedAt[id]) } }.orEmpty() }
            .sortedByDescending { it.call.atMs }
    }

    /** Core callbacks arrive on core threads; state changes happen on [scope]. */
    private inner class Listener(private val hostId: String, private val slot: Slot) : CallListener {
        /** Callbacks of a watch that was stopped (or replaced) are ignored. */
        private val current get() = watches[hostId] === slot

        override fun onCalls(calls: List<CallInfo>) {
            scope.launch {
                if (!current) return@launch
                syncingHosts = syncingHosts - hostId
                if (hostId !in feedProblems) liveHosts.add(hostId)
                checkedAt[hostId] = System.currentTimeMillis()
                val finalKeys = calls.filter { it.answer?.let { answer -> answer.state != CallAnswerState.CLAIMED } == true }
                    .map { "$hostId/${it.id}" }.toSet()
                val keys = calls.map { "$hostId/${it.id}" }.toSet()
                confirmedFinals.removeAll { it.startsWith("$hostId/") && it !in keys }
                confirmedFinals.addAll(finalKeys)
                replyNotes = replyNotes.filterKeys { it !in finalKeys && (!it.startsWith("$hostId/") || it in keys) }
                val previous = perHost[hostId].orEmpty().associateBy { it.id }
                perHost[hostId] = calls.map { it.copy(answer = mergeAnswer(previous[it.id]?.answer, it.answer)) }
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
                if (state != CallFeedState.Live) {
                    liveHosts.remove(hostId)
                    syncingHosts = syncingHosts - hostId
                }
                feedProblems = when (state) {
                    CallFeedState.Live -> {
                        if (hostId !in liveHosts) syncingHosts = syncingHosts + hostId
                        startFailures = startFailures - hostId
                        feedProblems - hostId
                    }
                    else -> feedProblems + (hostId to state)
                }
                publish()
            }
        }
    }
}

/** Answers are durable: a claim or an older snapshot cannot undo a final result. */
private fun mergeAnswer(current: CallAnswer?, incoming: CallAnswer?): CallAnswer? = when {
    current == null -> incoming
    incoming == null -> current
    current.state != CallAnswerState.CLAIMED && incoming.state == CallAnswerState.CLAIMED -> current
    current.state == CallAnswerState.CLAIMED && incoming.state != CallAnswerState.CLAIMED -> incoming
    incoming.atMs >= current.atMs -> incoming
    else -> current
}
