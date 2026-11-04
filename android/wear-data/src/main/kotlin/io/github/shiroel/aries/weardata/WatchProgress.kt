package io.github.shiroel.aries.weardata

import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest

/** Host timestamps order pushes and measure elapsed work; observedAtMs is the phone's receipt clock. */
data class WatchProgress(
    val hostId: String,
    val terminal: String,
    val name: String,
    val startedAtMs: Long,
    val childAgents: Int,
    val atMs: Long,
    val cancelled: Boolean = false,
    val observedAtMs: Long = 0,
) {
    init {
        require(hostId.isNotBlank() && hostId.length <= 512 && terminal.isNotBlank() && terminal.length <= 512)
        require(observedAtMs >= 0)
        require(name.length <= 128 && startedAtMs >= 0 && atMs >= startedAtMs && childAgents >= 0)
    }

    val dataPath: String get() = DATA_PATH + "/" + java.security.MessageDigest.getInstance("SHA-256")
        .digest("${hostId.length}:$hostId$terminal".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun toRequest(): PutDataRequest = PutDataMapRequest.create(dataPath).apply {
        dataMap.putInt("version", 1)
        dataMap.putString("host", hostId)
        dataMap.putString("terminal", terminal)
        dataMap.putString("name", name)
        dataMap.putLong("started_at_ms", startedAtMs)
        dataMap.putInt("child_agents", childAgents)
        dataMap.putLong("at_ms", atMs)
        dataMap.putBoolean("cancelled", cancelled)
        dataMap.putLong("observed_at_ms", observedAtMs)
    }.asPutDataRequest().setUrgent()

    companion object {
        const val DATA_PATH = "/aries/progress"
        const val MAX_AGE_MS = 30 * 60_000L
        fun isDataPath(path: String?) = path == DATA_PATH || path?.startsWith("$DATA_PATH/") == true
        fun fromBytes(bytes: ByteArray?): WatchProgress? = runCatching {
            require(bytes != null && bytes.size <= 4096)
            val map = DataMap.fromByteArray(bytes)
            require(map.getInt("version") == 1 && map.containsKey("started_at_ms") && map.containsKey("at_ms"))
            WatchProgress(requireNotNull(map.getString("host")), requireNotNull(map.getString("terminal")),
                map.getString("name").orEmpty(), map.getLong("started_at_ms"), map.getInt("child_agents"),
                map.getLong("at_ms"), map.getBoolean("cancelled"), map.getLong("observed_at_ms"))
        }.getOrNull()
    }
}
