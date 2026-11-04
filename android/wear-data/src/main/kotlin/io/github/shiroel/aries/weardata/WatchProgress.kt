package io.github.shiroel.aries.weardata

import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest

/** One most recently updated Watched Agent. Times are Unix milliseconds, never device uptime. */
data class WatchProgress(
    val hostId: String,
    val terminal: String,
    val name: String,
    val startedAtMs: Long,
    val childAgents: Int,
    val atMs: Long,
    val cancelled: Boolean = false,
) {
    init {
        require(hostId.isNotBlank() && hostId.length <= 512 && terminal.isNotBlank() && terminal.length <= 512)
        require(name.length <= 128 && startedAtMs >= 0 && atMs >= startedAtMs && childAgents >= 0)
    }

    fun toRequest(): PutDataRequest = PutDataMapRequest.create(DATA_PATH).apply {
        dataMap.putInt("version", 1)
        dataMap.putString("host", hostId)
        dataMap.putString("terminal", terminal)
        dataMap.putString("name", name)
        dataMap.putLong("started_at_ms", startedAtMs)
        dataMap.putInt("child_agents", childAgents)
        dataMap.putLong("at_ms", atMs)
        dataMap.putBoolean("cancelled", cancelled)
    }.asPutDataRequest().setUrgent()

    companion object {
        const val DATA_PATH = "/aries/progress"
        fun fromBytes(bytes: ByteArray?): WatchProgress? = runCatching {
            require(bytes != null && bytes.size <= 4096)
            val map = DataMap.fromByteArray(bytes)
            require(map.getInt("version") == 1 && map.containsKey("started_at_ms") && map.containsKey("at_ms"))
            WatchProgress(requireNotNull(map.getString("host")), requireNotNull(map.getString("terminal")),
                map.getString("name").orEmpty(), map.getLong("started_at_ms"), map.getInt("child_agents"),
                map.getLong("at_ms"), map.getBoolean("cancelled"))
        }.getOrNull()
    }
}
