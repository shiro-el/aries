package io.github.shiroel.aries.wear

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.wear.compose.material.dialog.Dialog
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import io.github.shiroel.aries.weardata.WatchAgent

/** Confirmation stays local; the Activity owns the pending request and its result. */
@Composable
internal fun WatchInterruptAction(agent: WatchAgent, session: WatchDetailSession) {
    var confirming by remember(agent.hostId, agent.terminal) { mutableStateOf(false) }
    val pending = session.stopping(agent)
    val working = agent.status.equals("working", true)
    LaunchedEffect(working) { if (!working) confirming = false }
    if (working || pending) InterruptChip(stringResource(if (pending) R.string.wear_interrupt_pending else R.string.wear_inbox_stop), working && !pending) {
        confirming = true
    }
    Dialog(showDialog = confirming && working, onDismissRequest = { confirming = false }) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), autoCentering = null,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 32.dp)) {
            item { Text(stringResource(R.string.wear_interrupt_confirm_body, agent.name), style = MaterialTheme.typography.body2) }
            item { InterruptChip(stringResource(R.string.wear_interrupt_confirm), !pending, primary = true) {
                confirming = false
                session.stop(agent)
            } }
            item { InterruptChip(stringResource(R.string.wear_interrupt_cancel), true) { confirming = false } }
        }
    }
}

@Composable
private fun InterruptChip(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    Chip(label = { Text(label) }, onClick = onClick, enabled = enabled,
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        shape = WatchConsoleShape, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun WatchStopResult(agent: WatchAgent, result: io.github.shiroel.aries.weardata.WatchInterruptResult, onDismiss: () -> Unit) {
    Dialog(showDialog = true, onDismissRequest = onDismiss) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), autoCentering = null,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 32.dp)) {
            item { Text(agent.name, style = MaterialTheme.typography.title3) }
            item { Text(stringResource(result.messageResource()), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            item { InterruptChip(stringResource(R.string.wear_inbox_back), true, onClick = onDismiss) }
        }
    }
}
