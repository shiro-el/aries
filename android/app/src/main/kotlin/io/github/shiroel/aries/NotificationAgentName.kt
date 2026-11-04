package io.github.shiroel.aries

import android.content.Context
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.ui.displayName

/** Push Calls carry a Terminal address; use the last SSH snapshot for its display name. */
internal object NotificationAgentName {
    fun resolve(context: Context, hostId: String, terminal: String, pushed: String? = null): String {
        if (!pushed.isNullOrBlank()) return NotificationMarkdown.render(pushed).plain
        val core = (context.applicationContext as? HasCore)?.core
        val saved = try {
            core?.lastKnownStates()?.find { it.hostId == hostId }?.agents?.find { it.address == terminal }
        } catch (_: CoreException) { null }
        return NotificationMarkdown.render(saved?.let(::displayName)?.takeIf { it.isNotBlank() } ?: terminal).plain
    }
}
