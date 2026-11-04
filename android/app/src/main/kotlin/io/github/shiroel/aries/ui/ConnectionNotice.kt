package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.R

/** The same Host connection status and recovery actions on every screen. */
@Composable
fun ConnectionNotice(link: HostLink, state: LinkState = link.state, firstLoad: Boolean = false, onRetry: () -> Unit = link::retryNow) {
    if (state == LinkState.Up) return
    val colors = MaterialTheme.colorScheme
    if (state == LinkState.Reconnecting) {
        ScreenLoading(stringResource(R.string.connect_host_reconnecting, link.host.name), compact = !firstLoad, labelDelayMillis = if (firstLoad) 0L else 1_500L, onRetry = onRetry)
        return
    }
    if (state !is LinkState.Down) return
    if (state.error is io.github.shiroel.aries.core.CoreException.BeaconNotInstalled) {
        Column(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.chat_status_no_beacon), color = colors.onSurfaceVariant)
            TextButton(onClick = { link.settings(state.error) }) {
                Text(stringResource(R.string.beacon_install_button))
            }
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().background(colors.errorContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnnouncedText(stringResource(R.string.connect_host_status, link.host.name),
            style = MaterialTheme.typography.titleSmall, color = colors.onErrorContainer)
        val problem = state.why
        AnnouncedText(stringResource(problem.title.res, *problem.title.args.toTypedArray()), color = colors.onErrorContainer)
        AnnouncedText(stringResource(problem.body.res, *problem.body.args.toTypedArray()),
            style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
        AnnouncedText(stringResource(R.string.connect_action_needed),
            style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
        Row {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.connect_retry_button), color = colors.onErrorContainer) }
            TextButton(onClick = { link.settings(state.error) }) { Text(stringResource(R.string.connect_settings_button), color = colors.onErrorContainer) }
        }
    }
}

/** Stream failure is distinct from an SSH failure and can retry on the existing line. */
@Composable
internal fun WatchFailureNotice(link: HostLink, error: CoreException) =
    CompactHostFailure(link, error, stopped = true)

@Composable
internal fun CompactHostFailure(link: HostLink, error: CoreException, stopped: Boolean) {
    val reason = messageText(compactConnectionReason(error))
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        val label = if (stopped) stringResource(R.string.agents_watch_stopped, link.host.name)
            else stringResource(R.string.agents_host_offline, link.host.name)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        TextButton(onClick = link::retryNow) { Text(stringResource(R.string.connect_retry_button)) }
        if (!stopped) IconButton(onClick = { link.settings(error) }) {
            Icon(painterResource(R.drawable.file_type_settings), stringResource(R.string.connect_settings_button),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Changed-key fingerprints belong in settings; the compact line names the actionable cause. */
internal fun compactConnectionReason(error: CoreException): Message {
    val problem = problemFor(error)
    return when (error) {
        is CoreException.HostKeyChanged, is CoreException.JumpHostKeyChanged,
        is CoreException.KeyLocked, is CoreException.Incompatible -> problem.title
        is CoreException.AuthRejected -> Message(R.string.connect_auth_rejected_short)
        is CoreException.JumpAuthRejected -> Message(R.string.connect_jump_auth_rejected_short)
        is CoreException.Protocol -> Message(R.string.connect_protocol_short)
        else -> problem.body
    }
}
