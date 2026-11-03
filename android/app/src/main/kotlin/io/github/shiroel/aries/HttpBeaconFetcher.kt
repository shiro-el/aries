package io.github.shiroel.aries

import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.FetchException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads pinned release artifacts (Beacon or herdr-core) on the phone. The core checks them
 * against the checksum pinned in the app before anything reaches a Host.
 */
class HttpBeaconFetcher : BeaconFetcher {
    override fun fetch(url: String): ByteArray {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode !in 200..299) {
                    throw FetchException.Failed("HTTP ${conn.responseCode} for $url")
                }
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) throw FetchException.Failed("$url is too large")
                    }
                    return out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            throw FetchException.Failed(e.message ?: e.toString())
        }
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
    }
}
