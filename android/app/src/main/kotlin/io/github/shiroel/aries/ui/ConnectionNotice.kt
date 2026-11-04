package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R

/** The same Host connection status and recovery actions on every screen. */
@Composable
fun ConnectionNotice(link: HostLink, state: LinkState = link.state, onRetry: () -> Unit = link::retryNow) {
    if (state == LinkState.Up) return
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(colors.errorContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.connect_host_status, link.host.name),
            style = MaterialTheme.typography.titleSmall, color = colors.onErrorContainer)
        if (state is LinkState.Down) {
            val problem = state.why
            Text(stringResource(problem.title.res, *problem.title.args.toTypedArray()), color = colors.onErrorContainer)
            Text(stringResource(problem.body.res, *problem.body.args.toTypedArray()),
                style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
            Text(stringResource(R.string.connect_action_needed),
                style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
        } else {
            Text(stringResource(R.string.reconnecting), color = colors.onErrorContainer)
        }
        Row {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.connect_retry_button), color = colors.onErrorContainer) }
            TextButton(onClick = { link.settings((state as? LinkState.Down)?.error) }) { Text(stringResource(R.string.connect_settings_button), color = colors.onErrorContainer) }
        }
    }
}
