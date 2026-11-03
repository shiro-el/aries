package io.github.shiroel.aries.dictation

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroqTranscriberTest {
    @Test fun postsAudioAsMultipartAndReturnsOnlyTheTranscript() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request = ""
        var authorization = ""
        var method = ""
        server.createContext("/openai/v1/audio/transcriptions") { exchange ->
            method = exchange.requestMethod
            authorization = exchange.requestHeaders.getFirst("Authorization")
            request = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = """{"text":"테스트 실행해 줘"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val audio = File.createTempFile("dictation", ".m4a").apply { writeText("fake-audio") }
        try {
            val client = GroqTranscriber("http://127.0.0.1:${server.address.port}/openai/v1/audio/transcriptions")
            assertEquals("테스트 실행해 줘", client.transcribe(audio, "test-key", DictationOptions(language = "ko"), "Aries"))
            assertEquals("POST", method)
            assertEquals("Bearer test-key", authorization)
            listOf("name=\"model\"\r\n\r\nwhisper-large-v3", "name=\"language\"\r\n\r\nko", "name=\"prompt\"\r\n\r\nAries", "name=\"response_format\"\r\n\r\njson", "filename=\"dictation.m4a\"", "Content-Type: audio/mp4", "fake-audio").forEach { assertTrue(it, request.contains(it)) }
        } finally { server.stop(0); audio.delete() }
    }
    @Test fun autoLanguageIsOmittedAndTurboAndWebmAreSupported() {
        withServer { server, url ->
            var request = ""
            server.createContext("/") { ex ->
                request = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                respond(ex, 200, """{"text":"ok"}""")
            }
            val file = File.createTempFile("dictation", ".webm").apply { writeText("audio") }
            try {
                assertEquals("ok", GroqTranscriber(url).transcribe(file, "fake", DictationOptions(model = DictationModel.TURBO), ""))
                assertFalse(request.contains("name=\"language\""))
                assertFalse(request.contains("name=\"prompt\""))
                assertTrue(request.contains("whisper-large-v3-turbo"))
                assertTrue(request.contains("audio/webm"))
            } finally { file.delete() }
        }
    }

    @Test fun rateLimitPreservesRetryAfterAndPreventsEarlyRetry() {
        withServer { server, url ->
            var requests = 0
            var clock = 1_000L
            server.createContext("/") { ex ->
                requests++
                ex.requestBody.readBytes()
                if (requests == 1) {
                    ex.responseHeaders.add("Retry-After", "3")
                    respond(ex, 429, "secret server detail")
                } else respond(ex, 200, """{"text":"retried"}""")
            }
            val audio = File.createTempFile("dictation", ".m4a").apply { writeText("audio") }
            try {
                val client = GroqTranscriber(url) { clock }
                val first = assertThrows(DictationFailure.RateLimited::class.java) { client.transcribe(audio, "fake", DictationOptions(), "") }
                assertEquals(3L, first.seconds)
                assertThrows(DictationFailure.RateLimited::class.java) { client.transcribe(audio, "fake", DictationOptions(), "") }
                assertEquals(1, requests)
                clock = 4_000
                assertEquals("retried", client.transcribe(audio, "fake", DictationOptions(), ""))
            } finally { audio.delete() }
        }
    }

    @Test fun rejectsBadResponsesWithoutLeakingResponseBodyOrKey() {
        withServer { server, url ->
            var status = 401
            var response = "key-or-transcript-secret"
            server.createContext("/") { ex -> ex.requestBody.readBytes(); respond(ex, status, response) }
            val audio = File.createTempFile("dictation", ".m4a").apply { writeText("audio") }
            val client = GroqTranscriber(url)
            try {
                val denied = assertThrows(DictationFailure.Unauthorized::class.java) { client.transcribe(audio, "secret-key", DictationOptions(), "") }
                assertFalse(denied.toString().contains("secret"))
                status = 503
                assertEquals(503, assertThrows(DictationFailure.Http::class.java) { client.transcribe(audio, "fake", DictationOptions(), "") }.status)
                status = 413
                assertThrows(DictationFailure.TooLarge::class.java) { client.transcribe(audio, "fake", DictationOptions(), "") }
                status = 200
                for (invalid in listOf("not-json", "{}", "{\"text\":null}", "{\"text\":42}")) {
                    response = invalid
                    assertThrows(DictationFailure.InvalidResponse::class.java) { client.transcribe(audio, "fake", DictationOptions(), "") }
                }
                audio.writeBytes(byteArrayOf())
                assertThrows(DictationFailure.EmptyAudio::class.java) { client.transcribe(audio, "fake", DictationOptions(), "") }
                java.io.RandomAccessFile(audio, "rw").use { it.setLength(25_000_001) }
                assertThrows(DictationFailure.TooLarge::class.java) { client.transcribe(audio, "fake", DictationOptions(), "") }
            } finally { audio.delete() }
        }
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, value: String) {
        val body = value.toByteArray()
        ex.sendResponseHeaders(status, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun withServer(test: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { test(server, "http://127.0.0.1:${server.address.port}/") }
        finally { server.stop(0) }
    }

}
