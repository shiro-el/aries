package io.github.shiroel.aries.wear

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Dialog
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.weardata.OpenWatchAgent
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchInterrupt
import io.github.shiroel.aries.weardata.WatchInterruptResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Reusable Inbox action; confirmation is local, authority and live checks stay on the phone. */
@Composable
fun WatchInterruptAction(agent: WatchAgent) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember(agent.hostId, agent.terminal) { mutableStateOf(false) }
    var pending by remember(agent.hostId, agent.terminal) { mutableStateOf(false) }
    var result by remember(agent.hostId, agent.terminal) { mutableStateOf<WatchInterruptResult?>(null) }
    val working = agent.status.equals("working", true)
    LaunchedEffect(working) { if (!working) confirming = false }

    InterruptChip(stringResource(if (pending) R.string.wear_interrupt_pending else R.string.wear_inbox_stop), working && !pending) {
        confirming = true
    }
    Dialog(showDialog = confirming && working, onDismissRequest = { confirming = false }) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), autoCentering = null,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 32.dp)) {
            item { Text(stringResource(R.string.wear_interrupt_confirm_body, agent.name), style = MaterialTheme.typography.body2) }
            item { InterruptChip(stringResource(R.string.wear_interrupt_confirm), !pending) {
                confirming = false
                pending = true
                result = null
                scope.launch {
                    try {
                        result = withTimeout(30_000) {
                            val store = WatchStateStore(context)
                            store.hydrate(context)
                            val phone = requireNotNull(store.phoneNode())
                            val reply = Wearable.getMessageClient(context).sendRequest(phone, WatchInterrupt.PATH,
                                OpenWatchAgent(agent.hostId, agent.terminal).toBytes()).await()
                            WatchInterruptResult.fromBytes(reply) ?: WatchInterruptResult.UNKNOWN
                        }
                    } catch (_: TimeoutCancellationException) { result = WatchInterruptResult.UNKNOWN }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { result = WatchInterruptResult.UNKNOWN }
                    finally { pending = false }
                }
            } }
            item { InterruptChip(stringResource(R.string.wear_interrupt_cancel), true) { confirming = false } }
        }
    }
    Dialog(showDialog = result != null, onDismissRequest = { result = null }) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), autoCentering = null,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 32.dp)) {
            item { Text(stringResource(when (result ?: WatchInterruptResult.UNKNOWN) {
                WatchInterruptResult.STOPPED -> R.string.wear_interrupt_stopped
                WatchInterruptResult.EXITED -> R.string.wear_interrupt_exited
                WatchInterruptResult.STILL_WORKING -> R.string.wear_interrupt_still_working
                WatchInterruptResult.NOT_WORKING -> R.string.wear_interrupt_not_working
                WatchInterruptResult.OPEN_PHONE -> R.string.wear_interrupt_open_phone
                WatchInterruptResult.BUSY -> R.string.wear_interrupt_busy
                WatchInterruptResult.UNKNOWN -> R.string.wear_interrupt_unknown
            }), style = MaterialTheme.typography.body2) }
            item { InterruptChip(stringResource(R.string.wear_inbox_back), true) { result = null } }
        }
    }
}

@Composable
private fun InterruptChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Chip(label = { Text(label) }, onClick = onClick, enabled = enabled,
        colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
}
