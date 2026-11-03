package io.github.shiroel.aries.weardata

import android.content.Intent
import android.net.Uri
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest

data class WatchAgent(val hostId: String, val terminal: String, val name: String, val status: String)

/** Versioned, small Data Layer contract; no SSH keys or conversation content. */
data class WatchSummary(
    val waiting: Int,
    val agents: List<WatchAgent>,
    val fetchedAtMs: Long,
    val languageTag: String = "",
) {
    init {
        require(languageTag.length <= 64)
        require(waiting >= 0 && fetchedAtMs >= 0 && agents.size <= 3)
        require(agents.all { it.hostId.isNotBlank() && it.terminal.isNotBlank() })
    }

    fun toRequest(): PutDataRequest = PutDataMapRequest.create(DATA_PATH).apply {
        dataMap.putInt("version", 1)
        dataMap.putString("language_tag", languageTag)
        dataMap.putInt("waiting", waiting)
        dataMap.putLong("fetched_at_ms", fetchedAtMs)
        dataMap.putDataMapArrayList("agents", ArrayList(agents.map { agent ->
            DataMap().apply {
                putString("host", agent.hostId)
                putString("terminal", agent.terminal)
                putString("name", agent.name)
                putString("status", agent.status)
            }
        }))
    }.asPutDataRequest()

    companion object {
        const val DATA_PATH = "/aries/last-known-state"
        const val OPEN_PATH = "/aries/open-agent"
        val EMPTY = WatchSummary(0, emptyList(), 0)

        fun fromBytes(bytes: ByteArray?): WatchSummary? = runCatching {
            require(bytes != null && bytes.size <= 100_000)
            val map = DataMap.fromByteArray(bytes)
            require(map.getInt("version") == 1)
            require(map.containsKey("waiting") && map.containsKey("fetched_at_ms"))
            val agents = requireNotNull(map.getDataMapArrayList("agents")).map {
                WatchAgent(requireNotNull(it.getString("host")), requireNotNull(it.getString("terminal")),
                    requireNotNull(it.getString("name")), requireNotNull(it.getString("status")))
            }
            WatchSummary(map.getInt("waiting"), agents, map.getLong("fetched_at_ms"), map.getString("language_tag") ?: "")
        }.getOrNull()
    }
}

/** MessageClient commands preserve machine-qualified addresses, without delimiter escaping. */
data class OpenWatchAgent(val hostId: String, val terminal: String) {
    fun remoteIntent(packageName: String): Intent = Intent(Intent.ACTION_VIEW,
        Uri.Builder().scheme("aries-watch").authority("agent")
            .appendQueryParameter("host", hostId).appendQueryParameter("terminal", terminal).build(),
    ).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName)

    fun toBytes(): ByteArray = DataMap().apply {
        putString("host", hostId)
        putString("terminal", terminal)
    }.toByteArray()

    companion object {
        fun fromIntent(intent: Intent?): OpenWatchAgent? {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val uri = intent.data ?: return null
            if (uri.scheme != "aries-watch" || uri.host != "agent" || uri.toString().length > 4096) return null
            val host = uri.getQueryParameter("host")?.takeIf { it.isNotBlank() } ?: return null
            val terminal = uri.getQueryParameter("terminal")?.takeIf { it.isNotBlank() } ?: return null
            return OpenWatchAgent(host, terminal)
        }

        fun fromBytes(bytes: ByteArray): OpenWatchAgent? = runCatching {
            require(bytes.size <= 4096)
            val map = DataMap.fromByteArray(bytes)
            val host = requireNotNull(map.getString("host")).also { require(it.isNotBlank()) }
            val terminal = requireNotNull(map.getString("terminal")).also { require(it.isNotBlank()) }
            OpenWatchAgent(host, terminal)
        }.getOrNull()
    }
}
