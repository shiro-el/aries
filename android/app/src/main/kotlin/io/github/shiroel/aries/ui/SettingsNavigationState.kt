package io.github.shiroel.aries.ui

import android.os.Bundle
import io.github.shiroel.aries.core.AgentView

/** Only navigation identity crosses process death. No connections, forms or credentials. */
internal data class SettingsReturnState(
    val location: LastLocation,
    val agentHosts: List<String>? = null,
    val terminalParent: String? = null,
)

internal data class SettingsNavigationState(
    val page: String,
    val hostId: String?,
    val origin: SettingsReturnState,
) {
    fun bundle() = Bundle().apply {
        putString("page", page)
        putString("host", hostId)
        putString("return-host", origin.location.hostId)
        putString("space", origin.location.spaceId)
        putString("tab", origin.location.tabId)
        putString("terminal", origin.location.terminal)
        putString("view", origin.location.view.name)
        putStringArrayList("agent-hosts", origin.agentHosts?.let { ArrayList(it) })
        putString("terminal-parent", origin.terminalParent)
    }

    companion object {
        fun read(state: Bundle?): SettingsNavigationState? {
            val page = state?.getString("page") ?: return null
            return SettingsNavigationState(page, state.getString("host"), SettingsReturnState(
                LastLocation(state.getString("return-host"), state.getString("space"), state.getString("tab"),
                    state.getString("terminal"), AgentView.entries.find { it.name == state.getString("view") } ?: AgentView.TERMINAL),
                state.getStringArrayList("agent-hosts"), state.getString("terminal-parent"),
            ))
        }
    }
}

/** Host setup identity only; QR credentials and trust decisions must be obtained again. */
internal data class HostSetupState(
    val method: HostAddMethod?,
    val addedHostId: String?,
    val setupHostId: String?,
) {
    fun writeTo(state: Bundle) {
        state.putString("host-add-method", method?.name)
        state.putString("host-add-saved", addedHostId)
        state.putString("host-add-follow", setupHostId)
    }

    companion object {
        fun read(state: Bundle?): HostSetupState? {
            if (state == null || !state.containsKey("host-add-method")) return null
            return HostSetupState(
                HostAddMethod.entries.find { it.name == state.getString("host-add-method") },
                state.getString("host-add-saved"), state.getString("host-add-follow"),
            )
        }
    }
}
