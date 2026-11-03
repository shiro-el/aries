package io.github.shiroel.aries.guidance

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

const val DEFAULT_APP_UPDATE_URL = "http://100.117.188.97:8799/aries-debug.json"

data class AppRelease(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

/** Only links that a browser can open; metadata never becomes an executable install request. */
fun isAppUpdateUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

/** Small, bounded metadata request. APK downloads belong to the user's browser. */
class HttpAppUpdates {
    fun fetch(url: String): AppRelease {
        require(isAppUpdateUrl(url))
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            require(connection.responseCode in 200..299)
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 256 * 1024)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val code = json.get("versionCode").toString().toLong()
            val name = json.getString("versionName").trim()
            val apkUrl = json.getString("apkUrl").trim()
            val notes = json.getString("notes")
            require(code > 0 && name.isNotEmpty() && isAppUpdateUrl(apkUrl))
            return AppRelease(code, name, apkUrl, notes)
        } finally {
            connection.disconnect()
        }
    }
}
