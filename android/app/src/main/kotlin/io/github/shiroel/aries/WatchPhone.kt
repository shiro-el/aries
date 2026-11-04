package io.github.shiroel.aries

import android.content.Context
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.LastKnownState
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.ui.withAgentSpaces
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object WatchPhone {
    private val languageChanges = MutableStateFlow(0L)
    fun languageChanged() { languageChanges.update { it + 1 } }

    /** Watches local storage only; the existing pull schedule owns all SSH activity. */
    fun start(context: Context, core: AriesCoreInterface, scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        val privacy = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> languageChanged() }
        privacy.registerOnSharedPreferenceChangeListener(listener)
        try {
            LastKnownStates.observe(core).retryWhen { error, _ ->
                if (error is CancellationException) false else { delay(5_000); true }
            }.combine(languageChanges) { states, _ -> states }.collect { states ->
                try {
                    val details = withCachedDetails(core, summary(core.hosts(), states),
                        privacy.getBoolean("enabled", false) && privacy.getBoolean("hide_previews", true))
                    Wearable.getDataClient(context).putDataItem(details.copy(
                        languageTag = AppLanguage.context(context).resources.configuration.locales[0].toLanguageTag(),
                    ).toRequest()).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Phones without Play services still work; a later local change retries publication.
                }
            }
        } finally { privacy.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Reads encrypted local Chat only; refresh scheduling owns all SSH connections. */
    suspend fun withCachedDetails(core: AriesCoreInterface, summary: WatchSummary, hideContent: Boolean): WatchSummary =
        summary.copy(inbox = summary.inbox.map { agent ->
            if (hideContent) agent.copy(requestBody = "", conversationSummary = "", requestId = "", choices = emptyList(), contentHidden = true)
            else {
                val entries = try { core.cachedChat(agent.hostId, agent.terminal)?.entries.orEmpty() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
                val question = if (agent.requestKind == "question") entries.lastOrNull {
                    val body = it.body
                    body is ChatEntryBody.Question && body.status == ChatToolStatus.RUNNING
                } else null
                val body = question?.body as? ChatEntryBody.Question
                val command = if (agent.requestKind == "permission") entries.lastOrNull {
                    val tool = it.body
                    tool is ChatEntryBody.Tool && tool.status == ChatToolStatus.RUNNING
                }?.body as? ChatEntryBody.Tool else null
                agent.copy(
                    requestBody = (body?.questions?.joinToString("\n") { it.text }
                        ?: command?.details?.command ?: agent.requestBody),
                    requestId = question?.id.orEmpty(),
                    choices = body?.questions?.flatMap { it.options.map { option -> option.label } }.orEmpty(),
                    conversationSummary = entries.mapNotNull { (it.body as? ChatEntryBody.Agent)?.text }
                        .lastOrNull().orEmpty(),
                )
            }
        })

    fun summary(hosts: List<Host>, states: List<LastKnownState>): WatchSummary {
        val byHost = states.associateBy { it.hostId }
        val known = hosts.mapNotNull { byHost[it.id] }
        val entries = hosts.flatMap { host ->
            withAgentSpaces(agentFamily(host, byHost[host.id]?.agents.orEmpty()), byHost[host.id]?.structure)
        }
        val ordered = orderAgents(entries)
        fun row(entry: io.github.shiroel.aries.ui.AgentEntry): WatchAgent {
            val terminal = entry.terminal
            val blocked = terminal.agentStatus as? AgentStatus.Blocked
            return WatchAgent(entry.host.id, terminal.address, displayName(terminal), when (terminal.agentStatus) {
                is AgentStatus.Blocked -> "blocked"
                is AgentStatus.Error -> "error"
                AgentStatus.Done -> "done"
                AgentStatus.Working -> "working"
                else -> "idle"
            }, hostName = entry.host.name,
                project = entry.space?.name ?: terminal.foreground?.cwd ?: terminal.cwd.orEmpty(),
                requestBody = blocked?.message.orEmpty(), requestKind = when (blocked?.reason) {
                    BlockedReason.PERMISSION -> "permission"
                    BlockedReason.QUESTION -> "question"
                    else -> ""
                })
        }
        return WatchSummary(
            waiting = entries.count { it.terminal.parent == null && isWaiting(it.terminal.agentStatus) },
            agents = ordered.take(3).map(::row),
            inbox = ordered.sortedBy { if (it.terminal.agentStatus is AgentStatus.Blocked) 0 else 1 }
                .take(WatchSummary.MAX_INBOX_AGENTS).map(::row),
            totalAgents = entries.size,
            fetchedAtMs = known.minOfOrNull { it.fetchedAtMs.toLong().coerceAtLeast(0) } ?: 0,
        )
    }
}
