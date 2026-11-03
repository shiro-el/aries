package io.github.shiroel.aries

import android.content.Context
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.LastKnownState
import io.github.shiroel.aries.ui.AgentKey
import io.github.shiroel.aries.ui.agentFamily
import io.github.shiroel.aries.ui.displayName
import io.github.shiroel.aries.ui.isWaiting
import io.github.shiroel.aries.ui.orderAgents
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object WatchPhone {
    /** Watches local storage only; the existing pull schedule owns all SSH activity. */
    fun start(context: Context, core: AriesCoreInterface, scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        LastKnownStates.observe(core).retryWhen { error, _ ->
            if (error is CancellationException) false else { delay(5_000); true }
        }.collect { states ->
            try {
                Wearable.getDataClient(context).putDataItem(summary(core.hosts(), states).toRequest()).await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Phones without Play services still work; a later local change retries publication.
            }
        }
    }

    fun summary(hosts: List<Host>, states: List<LastKnownState>): WatchSummary {
        val byHost = states.associateBy { it.hostId }
        val known = hosts.mapNotNull { byHost[it.id] }
        val entries = hosts.flatMap { host -> agentFamily(host, byHost[host.id]?.agents.orEmpty()) }
        val keys = entries.map { it.key }.toSet()
        // Fold each family to its parent, while retaining the shared effective family priority.
        val roots = orderAgents(entries).filter { entry ->
            entry.terminal.parent?.let { AgentKey(entry.host.id, it) !in keys } ?: true
        }
        return WatchSummary(
            waiting = entries.count { it.terminal.parent == null && isWaiting(it.terminal.agentStatus) },
            agents = roots.take(3).map { entry ->
                val terminal = entry.terminal
                WatchAgent(entry.host.id, terminal.address, displayName(terminal).take(256), when (terminal.agentStatus) {
                    is AgentStatus.Blocked -> "blocked"
                    is AgentStatus.Error -> "error"
                    AgentStatus.Done -> "done"
                    AgentStatus.Working -> "working"
                    else -> "idle"
                })
            },
            fetchedAtMs = known.minOfOrNull { it.fetchedAtMs.toLong().coerceAtLeast(0) } ?: 0,
        )
    }
}
