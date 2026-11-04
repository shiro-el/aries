package io.github.shiroel.aries.ui

import android.content.Context
import android.content.SharedPreferences
import io.github.shiroel.aries.core.AgentView

/** Phone-local navigation identity. A null Host means the Host list. */
data class LastLocation(
    val hostId: String? = null,
    val spaceId: String? = null,
    val tabId: String? = null,
    val terminal: String? = null,
    val view: AgentView = AgentView.TERMINAL,
)

/** Stores IDs only; opening a saved location still goes through normal Host authentication. */
class LastLocationStore(private val preferences: SharedPreferences) {
    fun load(): LastLocation = LastLocation(
        preferences.getString("host", null),
        preferences.getString("space", null),
        preferences.getString("tab", null),
        preferences.getString("terminal", null),
        AgentView.entries.find { it.name == preferences.getString("view", null) } ?: AgentView.TERMINAL,
    )

    fun save(location: LastLocation) {
        preferences.edit()
            .putString("host", location.hostId)
            .putString("space", location.spaceId)
            .putString("tab", location.tabId)
            .putString("terminal", location.terminal)
            .putString("view", location.view.name)
            .apply()
    }

    /** Thread keeps its last Agent even when Host management becomes the last route. */
    fun loadAgent(): LastLocation? = preferences.getString("agent_host", null)?.let { host ->
        preferences.getString("agent_terminal", null)?.let { terminal ->
            LastLocation(hostId = host, terminal = terminal, view = AgentView.CHAT)
        }
    } ?: load().takeIf { it.terminal != null }

    fun saveAgent(location: LastLocation) {
        preferences.edit().putString("agent_host", location.hostId)
            .putString("agent_terminal", location.terminal).apply()
    }

    companion object {
        fun from(context: Context) = LastLocationStore(
            context.applicationContext.getSharedPreferences("last_location", Context.MODE_PRIVATE),
        )
    }
}
