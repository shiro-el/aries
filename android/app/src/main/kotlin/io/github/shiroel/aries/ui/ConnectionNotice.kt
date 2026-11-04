package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import kotlinx.coroutines.delay

/** The same Host connection status and recovery actions on every screen. */
@Composable
fun ConnectionNotice(link: HostLink, state: LinkState = link.state, onRetry: () -> Unit = link::retryNow) {
    if (state == LinkState.Up) return
    val colors = MaterialTheme.colorScheme
    if (state == LinkState.Reconnecting) {
        var showLabel by remember(link) { mutableStateOf(false) }
        LaunchedEffect(link) {
            delay(1_500)
            showLabel = true
        }
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.primary)
            if (showLabel) {
                AnnouncedText(stringResource(R.string.connect_host_reconnecting, link.host.name),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        return
    }
    if (state !is LinkState.Down) return
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
