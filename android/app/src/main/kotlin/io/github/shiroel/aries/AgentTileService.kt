package io.github.shiroel.aries

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.LastKnownState
import io.github.shiroel.aries.ui.agentFamily
import io.github.shiroel.aries.ui.orderAgents
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared projection of persisted SSH pulls. Reading it never connects to a Host. */
object AgentSurfaces {
    const val OPEN_ATTENTION = "io.github.shiroel.aries.OPEN_ATTENTION"

    fun entries(core: AriesCoreInterface) = entries(core.hosts(), core.lastKnownStates())

    fun entries(hosts: List<Host>, states: List<LastKnownState>) =
        hosts.flatMapIndexed { index, host ->
            agentFamily(host, states.firstOrNull { it.hostId == host.id }?.agents.orEmpty()
                .filter { it.state == "live" }).map { it.copy(hostOrder = index) }
        }.let(::orderAgents)

    fun attention(core: AriesCoreInterface): PushTarget? = entries(core).firstOrNull {
        it.terminal.agentStatus is AgentStatus.Blocked || it.terminal.agentStatus is AgentStatus.Error
    }?.let { PushTarget(it.host.id, it.terminal.address) }

    fun intent(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(OPEN_ATTENTION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/** Listening is bounded by the open QS panel; there is no network watch or foreground service. */
class AgentTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private val refreshLock = Mutex()
    private val core get() = (application as HasCore).core

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            launch {
                LastKnownStates.observe(core).retryWhen { error, _ ->
                    if (error is CancellationException) false else { delay(5_000); true }
                }.collect { refreshTile() }
            }
            while (isActive) {
                refreshTile()
                delay(60_000)
            }
        }
    }

    suspend fun refreshTile(nowMs: Long = System.currentTimeMillis()) = refreshLock.withLock {
        val context = AppLanguage.context(this)
        val tile = qsTile ?: return@withLock
        val (count, oldest, complete) = try {
            withContext(Dispatchers.IO) {
                val states = core.lastKnownStates()
                val hosts = core.hosts()
                Triple(AgentSurfaces.entries(hosts, states).count {
                    it.terminal.agentStatus is AgentStatus.Blocked || it.terminal.agentStatus is AgentStatus.Error
                }, states.filter { state -> hosts.any { it.id == state.hostId } }.minOfOrNull { it.fetchedAtMs },
                    hosts.isNotEmpty() && hosts.all { host -> states.any { it.hostId == host.id } })
            }
        } catch (_: CoreException) { return@withLock } // Retain the displayed value until a successful read.
        val age = if (!complete || oldest == null) context.getString(R.string.surfaces_age_unknown)
            else context.getString(R.string.surfaces_age_minutes, ((nowMs.coerceAtLeast(0).toULong().coerceAtLeast(oldest) - oldest) / 60_000uL).toLong())
        tile.label = context.getString(R.string.qs_waiting_label, count)
        // API 28 has no subtitle: retain the age in its visible label.
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = age
        else tile.label = context.getString(R.string.qs_label_with_age, tile.label, age)
        tile.contentDescription = context.getString(R.string.qs_label_with_age, context.getString(R.string.qs_waiting_label, count), age)
        tile.state = if (count > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        TileServiceCompat.startActivityAndCollapse(this, PendingIntentActivityWrapper(
            this, 77, AgentSurfaces.intent(this), PendingIntent.FLAG_UPDATE_CURRENT, false,
        ))
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
