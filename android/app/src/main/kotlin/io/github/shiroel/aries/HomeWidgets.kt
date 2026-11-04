package io.github.shiroel.aries

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.LastKnownState
import io.github.shiroel.aries.ui.AgentTimeUnit
import io.github.shiroel.aries.ui.agentDuration
import io.github.shiroel.aries.ui.agentFamily
import io.github.shiroel.aries.ui.agentStatusLabel
import io.github.shiroel.aries.ui.displayName
import io.github.shiroel.aries.ui.orderAgents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/** Home surfaces render only the core's persisted Last-known state. */
object HomeWidgets {
    const val ACTION_REFRESH = "io.github.shiroel.aries.REFRESH_HOME_WIDGETS"
    private val providers = listOf(WaitingHomeWidget::class.java to false, AgentsHomeWidget::class.java to true)

    fun hasWidgets(context: Context): Boolean = providers.any {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it.first)).isNotEmpty()
    }

    /** Also called after failed pulls: the saved fetch time stays, but its displayed age advances. */
    fun update(context: Context, core: AriesCoreInterface, states: List<LastKnownState>? = null) {
        val manager = AppWidgetManager.getInstance(context)
        val installed = providers.map { (provider, expanded) -> manager.getAppWidgetIds(ComponentName(context, provider)) to expanded }
        if (installed.none { it.first.isNotEmpty() }) return
        try {
            val hosts = core.hosts()
            val known = states ?: core.lastKnownStates()
            installed.forEach { (ids, expanded) ->
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, render(context, hosts, known, expanded))
            }
        } catch (_: CoreException) {
            // Local storage failures preserve the last rendered value too.
        }
    }

    /** A local core watch, not a Host connection. The Application owns and cancels this job. */
    fun observe(context: Context, core: AriesCoreInterface, scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        LastKnownStates.observe(core).retryWhen { cause, _ ->
            if (cause is CoreException) { delay(1_000); true } else false
        }.collect { update(context, core, it) }
    }

    fun render(
        context: Context,
        hosts: List<Host>,
        states: List<LastKnownState>,
        expanded: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): RemoteViews {
        val context = AppLanguage.context(context)
        val byHost = states.associateBy { it.hostId }
        val known = hosts.mapNotNull { byHost[it.id] }
        val count = known.sumOf { state -> state.agents.count { it.needsAttention } }
        val age = if (known.isEmpty() || known.size != hosts.size) {
            context.getString(R.string.widget_no_saved_state)
        } else {
            val fetched = known.minOf { it.fetchedAtMs }
            io.github.shiroel.aries.ui.relativeAge(context, fetched, nowMillis)
        }
        val agents = orderAgents(hosts.flatMap { host -> agentFamily(host, byHost[host.id]?.agents.orEmpty()) })
        return RemoteViews(context.packageName, if (expanded) R.layout.widget_agents else R.layout.widget_waiting).apply {
            setContentDescription(R.id.widget_refresh, context.getString(R.string.widget_refresh_button))
            setTextViewText(R.id.widget_count, if (hosts.isNotEmpty() && known.isEmpty()) {
                context.getString(R.string.widget_waiting_unknown)
            } else context.getString(R.string.widget_waiting_count, count))
            setTextViewText(R.id.widget_age, age)
            setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(
                context, 0, Intent(context, HomeWidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            if (expanded) {
                setTextViewText(R.id.widget_empty, context.getString(R.string.widget_agents_empty))
                removeAllViews(R.id.widget_agents)
                setViewVisibility(R.id.widget_empty, if (agents.isEmpty()) View.VISIBLE else View.GONE)
                agents.take(3).forEach { entry ->
                    val terminal = entry.terminal
                    val duration = agentDuration(terminal.statusMs, nowMillis)
                    val elapsed = when (duration?.unit) {
                        null -> context.getString(R.string.console_agent_age_unknown)
                        AgentTimeUnit.LessThanMinute -> context.getString(R.string.agents_elapsed_less_than_minute)
                        AgentTimeUnit.Minute -> context.getString(R.string.agents_elapsed_minutes, duration.value)
                        AgentTimeUnit.Hour -> context.getString(R.string.agents_elapsed_hours, duration.value)
                        AgentTimeUnit.Day -> context.getString(R.string.agents_elapsed_days, duration.value)
                    }
                    val status = terminal.agentStatus?.let { context.getString(agentStatusLabel(it)) }.orEmpty()
                    addView(R.id.widget_agents, RemoteViews(context.packageName, R.layout.widget_agent_row).apply {
                        setTextViewText(R.id.widget_agent_name, displayName(terminal))
                        setTextViewText(R.id.widget_agent_detail, context.getString(R.string.widget_agent_detail, status, entry.host.name, elapsed))
                        setOnClickPendingIntent(R.id.widget_agent_row, PushTarget(entry.host.id, terminal.address).pendingIntent(context))
                    })
                }
            }
        }
    }
}

/** The button delegates SSH/key eligibility and work deduplication to ticket 76. */
class HomeWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HomeWidgets.ACTION_REFRESH) LastKnownStates.requestRefresh(context)
    }
}
