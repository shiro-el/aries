package io.github.shiroel.aries

import io.github.shiroel.aries.weardata.WatchCommand
import io.github.shiroel.aries.weardata.WatchResult
import kotlinx.coroutines.*
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import io.github.shiroel.aries.weardata.OpenWatchAgent
import io.github.shiroel.aries.weardata.WatchSummary

/** Data Layer verifies package/signature. Mutations use fresh Host state and background-safe keys. */
class WatchPhoneService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun rpc(block: suspend () -> ByteArray): Task<ByteArray> {
        val completion = TaskCompletionSource<ByteArray>()
        scope.launch {
            try { completion.trySetResult(withTimeout(13_000) { block() }) }
            catch (cancelled: CancellationException) {
                completion.trySetResult(WatchResult.UNKNOWN.toBytes())
                if (cancelled !is TimeoutCancellationException) throw cancelled
            } catch (_: Exception) { completion.trySetResult(WatchResult.FAILED.toBytes()) }
        }
        return completion.task
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path == WatchCommand.DETAILS_PATH) {
            val target = OpenWatchAgent.fromBytes(request) ?: return Tasks.forResult(byteArrayOf())
            return rpc {
                val core = (application as HasCore).core
                WatchActions.details(this, core, target)?.let { WatchCommand(it).toBytes() } ?: byteArrayOf()
            }
        }
        if (path == WatchCommand.ANSWER_PATH || path == WatchCommand.REPLY_PATH) {
            val command = WatchCommand.fromBytes(request) ?: return Tasks.forResult(WatchResult.FAILED.toBytes())
            // Do not let an answer RPC smuggle in an ordinary message (or vice versa).
            if ((path == WatchCommand.REPLY_PATH) != command.text.isNotBlank())
                return Tasks.forResult(WatchResult.FAILED.toBytes())
            return rpc {
                val result = WatchActions.execute(this, (application as HasCore).core, command)
                if (result == WatchResult.SENT) runCatching { LastKnownStates.requestRefresh(this, command.agent.hostId) }
                result.toBytes()
            }
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
}
