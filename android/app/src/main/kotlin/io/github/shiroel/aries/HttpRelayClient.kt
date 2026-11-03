package io.github.shiroel.aries

import io.github.shiroel.aries.core.FetchException
import io.github.shiroel.aries.core.HttpClient
import io.github.shiroel.aries.core.HttpRequest
import io.github.shiroel.aries.core.HttpResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The core's HTTP client for the Relay. The core calls it off the main thread. */
class HttpRelayClient : HttpClient {
    override fun send(request: HttpRequest): HttpResponse {
        try {
            val conn = URL(request.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.requestMethod = request.method
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                if (request.body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(request.body.size)
                    conn.outputStream.use { it.write(request.body) }
                }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                return HttpResponse(status.toUShort(), body)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            throw FetchException.Failed(e.message ?: e.toString())
        }
    }
}
