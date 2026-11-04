package io.github.shiroel.aries.weardata

import android.content.Intent
import android.net.Uri
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest

/** Only request text and a bounded last-response excerpt travel to the paired watch; never keys. */
data class WatchAgent(
    val hostId: String, val terminal: String, val name: String, val status: String,
    val hostName: String = "", val project: String = "",
    val requestBody: String = "", val conversationSummary: String = "",
    val requestId: String = "", val requestKind: String = "",
    val choices: List<String> = emptyList(),
    val contentHidden: Boolean = false,
)

/**
 * V2 uses an additive extension inside the v1 envelope so installed v1 watches still
 * show their three-Agent projection. New watches also accept explicit v2 envelopes.
 * Content follows the phone's preview policy. No full transcripts or SSH credentials.
 */
data class WatchSummary(
    val waiting: Int,
    val agents: List<WatchAgent>,
    val fetchedAtMs: Long,
    val languageTag: String = "",
    val inbox: List<WatchAgent> = agents,
    val totalAgents: Int = inbox.size,
) {
    init {
        require(languageTag.length <= 64)
        require(waiting >= 0 && fetchedAtMs >= 0 && agents.size <= 3 && totalAgents >= 0)
        require((agents + inbox).all { it.hostId.isNotBlank() && it.terminal.isNotBlank() })
    }

    fun toRequest(): PutDataRequest {
        val rows = inbox.take(MAX_INBOX_AGENTS).map { it.bounded() }.toMutableList()
        fun request() = PutDataMapRequest.create(DATA_PATH).apply {
            dataMap.putInt("version", 1)
            dataMap.putInt("inbox_version", 2)
            dataMap.putString("language_tag", languageTag)
            dataMap.putInt("waiting", waiting)
            dataMap.putInt("total_agents", maxOf(totalAgents, inbox.size))
            dataMap.putLong("fetched_at_ms", fetchedAtMs)
            dataMap.putDataMapArrayList("agents", ArrayList(agents.map { it.bounded().toMap(false) }))
            dataMap.putDataMapArrayList("inbox", ArrayList(rows.map { it.toMap(true) }))
        }.asPutDataRequest()
        var result = request()
        while (requireNotNull(result.data).size > MAX_PAYLOAD_BYTES && rows.isNotEmpty()) {
            rows.removeAt(rows.lastIndex)
            result = request()
        }
        require(requireNotNull(result.data).size <= MAX_PAYLOAD_BYTES)
        return result
    }

    companion object {
        const val DATA_PATH = "/aries/last-known-state"
        const val OPEN_PATH = "/aries/open-agent"
        const val REFRESH_PATH = "/aries/refresh-inbox"
        const val MAX_INBOX_AGENTS = 32
        const val MAX_REQUEST_CHARS = 1200
        const val MAX_SUMMARY_CHARS = 600
        const val MAX_PAYLOAD_BYTES = 100_000
        val EMPTY = WatchSummary(0, emptyList(), 0)

        fun fromBytes(bytes: ByteArray?): WatchSummary? = runCatching {
            require(bytes != null && bytes.size <= MAX_PAYLOAD_BYTES)
            val map = DataMap.fromByteArray(bytes)
            require(map.getInt("version") in 1..2)
            require(map.containsKey("waiting") && map.containsKey("fetched_at_ms"))
            val agents = requireNotNull(map.getDataMapArrayList("agents")).map { it.toAgent() }
            val inbox = if (map.getInt("inbox_version") == 2 || map.getInt("version") == 2)
                map.getDataMapArrayList("inbox")?.also { require(it.size <= MAX_INBOX_AGENTS) }?.map { it.toAgent() } ?: agents
            else agents
            WatchSummary(map.getInt("waiting"), agents, map.getLong("fetched_at_ms"), map.getString("language_tag") ?: "", inbox,
                if (map.containsKey("total_agents")) map.getInt("total_agents") else inbox.size)
        }.getOrNull()
    }
}

/** Cut by UTF-16 length without leaving half of an emoji at the boundary. */
private fun String.limit(max: Int): String {
    if (length <= max) return this
    val end = if (Character.isHighSurrogate(this[max - 2])) max - 2 else max - 1
    return substring(0, end) + "…"
}

private fun WatchAgent.bounded(): WatchAgent {
    require(hostId.length <= 512 && terminal.length <= 512)
    return copy(name = name.limit(128), status = status.limit(32), hostName = hostName.limit(128), project = project.limit(256),
        requestBody = if (contentHidden) "" else requestBody.limit(WatchSummary.MAX_REQUEST_CHARS),
        conversationSummary = if (contentHidden) "" else conversationSummary.limit(WatchSummary.MAX_SUMMARY_CHARS),
        requestId = if (contentHidden) "" else requestId.limit(256), requestKind = requestKind.limit(32),
        choices = if (contentHidden) emptyList() else choices.take(8).map { it.limit(128) })
}

private fun WatchAgent.toMap(details: Boolean) = DataMap().apply {
    putString("host", hostId); putString("terminal", terminal)
    putString("name", name); putString("status", status)
    if (details) {
        putString("host_name", hostName); putString("project", project)
        putString("request_body", requestBody); putString("conversation_summary", conversationSummary)
        putString("request_id", requestId); putString("request_kind", requestKind)
        putStringArrayList("choices", ArrayList(choices)); putBoolean("content_hidden", contentHidden)
    }
}

private fun DataMap.toAgent(): WatchAgent = WatchAgent(
    requireNotNull(getString("host")), requireNotNull(getString("terminal")),
    getString("name") ?: "", getString("status") ?: "idle",
    getString("host_name") ?: "", getString("project") ?: "",
    getString("request_body") ?: "", getString("conversation_summary") ?: "",
    getString("request_id") ?: "", getString("request_kind") ?: "",
    getStringArrayList("choices") ?: emptyList(), getBoolean("content_hidden"),
).bounded()

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
