package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import kotlinx.coroutines.delay

/** A bounded first load, or a thin refresh above content that remains readable. */
@Composable
internal fun ScreenLoading(label: String, compact: Boolean = false, labelDelayMillis: Long = 0L, onRetry: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var expired by remember(attempt) { mutableStateOf(false) }
    var showLabel by remember(attempt) { mutableStateOf(labelDelayMillis == 0L) }
    LaunchedEffect(attempt, labelDelayMillis) {
        delay(labelDelayMillis)
        showLabel = true
    }
    LaunchedEffect(attempt) {
        delay(20_000L)
        expired = true
    }
    Column(
        if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = if (compact) Arrangement.Top else Arrangement.Center,
    ) {
        if (!expired) {
            if (compact) ConsoleProgress(Modifier.fillMaxWidth().height(2.dp))
            else ConsoleBusyIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            if (showLabel) Text(label, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        } else {
            AnnouncedText(stringResource(R.string.loading_slow), Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { attempt++; onRetry() }) { Text(stringResource(R.string.connect_retry_button)) }
        }
    }
}
