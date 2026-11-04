package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CallUrgency

/** Identity, rather than snapshots or recomposition, owns the foreground haptic. */
@Composable
internal fun CallAttentionEffect(entry: CallEntry?) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(entry?.key) {
        entry ?: return@LaunchedEffect
        val feedback = when (entry.call.urgency) {
            CallUrgency.RECORD -> return@LaunchedEffect
            CallUrgency.CHECK -> HapticFeedbackType.Confirm
            CallUrgency.URGENT -> HapticFeedbackType.LongPress
            CallUrgency.EMERGENCY -> HapticFeedbackType.Reject
        }
        haptic.performHapticFeedback(feedback)
    }
}

/** Persistent, compact attention; opening the inbox never answers the Call. */
@Composable
internal fun CallAttentionBanner(entry: CallEntry, callerName: String?, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = when (entry.call.urgency) {
        CallUrgency.RECORD, CallUrgency.CHECK -> colors.primary
        CallUrgency.URGENT -> statusColor(io.github.shiroel.aries.core.AgentStatus.Blocked(null, null))
        CallUrgency.EMERGENCY -> colors.error
    }
    val caller = stringResource(R.string.call_inbox_caller, callerName ?: entry.call.terminal.orEmpty(), entry.host.name)
    val open = stringResource(R.string.call_banner_open)
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainerHigh).testTag("call_banner")
            .clickable(onClickLabel = open, role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.call_banner_meta, urgencyLabel(entry.call.urgency), caller),
                color = accent, style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.call.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.call.body.isNotEmpty()) Text(entry.call.body, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDismiss) {
            Icon(painterResource(R.drawable.ic_x), stringResource(R.string.call_banner_dismiss), Modifier.size(20.dp))
        }
    }
    HorizontalDivider(color = accent)
}
