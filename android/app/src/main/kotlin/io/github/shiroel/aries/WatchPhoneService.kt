package io.github.shiroel.aries

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import io.github.shiroel.aries.weardata.OpenWatchAgent
import io.github.shiroel.aries.weardata.WatchSummary

/** Data Layer verifies package/signature. Acknowledge only an existing cached Agent. */
class WatchPhoneService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
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
