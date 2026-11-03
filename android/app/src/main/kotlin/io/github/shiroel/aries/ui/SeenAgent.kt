package io.github.shiroel.aries.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.shiroel.aries.ObserveNotificationTarget
import io.github.shiroel.aries.PushTarget
import io.github.shiroel.aries.core.CoreException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Shares attention only while an Agent is drawn in the resumed app. A separate
 * subscription also covers callers opened without a Browse/Agents watch.
 * The viewing session survives the Chat/Terminal toggle and a reconnect, but
 * ends when covered, navigated away from, or paused. This lets another client's
 * mark-unread stand until the person looks again (herdr-app docs/labels.md).
 */
@Composable
internal fun SeenAgentEffect(screen: Screen, enabled: Boolean) {
    val (link, address) = when (screen) {
        is Screen.Chat -> screen.chat.link to screen.chat.terminal.address
        is Screen.Terminal -> screen.viewer.link to screen.viewer.terminal.address
        else -> null to null
    }
    // Share the same drawn/uncovered Agent boundary with system notifications.
    ObserveNotificationTarget(if (enabled && link != null && address != null) PushTarget(link.host.id, address) else null)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(link, address, enabled, lifecycle) {
        if (!enabled || link == null || address == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var looked: ULong? = null
            try {
                link.keep { conn, _ ->
                    val watch = conn.watchTerminals()
                    try {
                        while (true) {
                            val terminal = watch.next().find { it.address == address } ?: continue
                            if (terminal.state != "live" || terminal.agentStatus == null) continue
                            if (looked?.let { terminal.turn <= it } == true) continue
                            currentCoroutineContext().ensureActive()
                            conn.markSeen(address, terminal.turn)
                            looked = terminal.turn
                        }
                    } finally {
                        watch.stop()
                        watch.close()
                    }
                }
            } catch (_: CoreException) {
                // Passive metadata: try again on the next viewing session.
            }
        }
    }
}
