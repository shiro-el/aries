package io.github.shiroel.aries

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.WearableListenerService
import io.github.shiroel.aries.weardata.OpenWatchAgent
import io.github.shiroel.aries.weardata.WatchSummary
import io.github.shiroel.aries.weardata.WatchInterrupt
import io.github.shiroel.aries.weardata.WatchInterruptResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Data Layer verifies package/signature. Acknowledge only an existing cached Agent. */
class WatchPhoneService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val interrupts by lazy { WatchInterruptHandler((application as HasCore).core) }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path == WatchInterrupt.PATH) {
            val command = OpenWatchAgent.fromBytes(request) ?: return Tasks.forResult(byteArrayOf())
            val reply = TaskCompletionSource<ByteArray>()
            scope.launch {
                try { reply.trySetResult(interrupts.interrupt(command).toBytes()) }
                finally { reply.trySetResult(WatchInterruptResult.UNKNOWN.toBytes()) }
            }
            return reply.task
        }
        if (path == WatchSummary.REFRESH_PATH) {
            if (request.isNotEmpty()) return Tasks.forResult(byteArrayOf())
            LastKnownStates.requestRefresh(this)
            return Tasks.forResult(byteArrayOf(1))
        }
        if (path != WatchSummary.OPEN_PATH) return null
        val command = OpenWatchAgent.fromBytes(request)
        val valid = command != null && runCatching {
            val core = (application as HasCore).core
            core.lastKnownState(command.hostId)?.agents?.any { it.address == command.terminal } == true
        }.getOrDefault(false)
        // The watch launches the acknowledged link with RemoteActivityHelper. A listener
        // service cannot reliably start an Activity from the background on modern Android.
        return Tasks.forResult(if (valid) command!!.toBytes() else byteArrayOf())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
