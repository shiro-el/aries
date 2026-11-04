package io.github.shiroel.aries.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.lifecycle.lifecycleScope
import io.github.shiroel.aries.core.*
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A reproducible, network-free workload using the real ChatScreen and ChatModel watch path. */
@OptIn(InternalComposeTracingApi::class)
class ChatProfileActivity : ComponentActivity() {
    private val updates = Channel<ChatSnapshot>(Channel.UNLIMITED)
    private val history by lazy { (0 until 300).map { index ->
        val body = when (index % 10) {
            0 -> ChatEntryBody.User("Please review change $index and explain the result." +
                "\n\nCheck `src/change$index.kt`, preserve the existing behavior, and explain the tests before editing."
                    .repeat(intent.getIntExtra("prompt-repeats", 0).coerceIn(0, 1000)))
            1 -> ChatEntryBody.Thinking("Considering the constraints for change $index.\n\n" + "Check the existing behavior before editing. ".repeat(12))
            2 -> ChatEntryBody.Tool("Read", "/repo/src/change$index.kt", "{\"path\":\"src/change$index.kt\"}", ChatToolStatus.DONE, "Read the file successfully.")
            3 -> ChatEntryBody.Agent("## Change $index\n\n" + "A paragraph with **emphasis**, `code`, and a [link](https://example.org). ".repeat(20) + "\n\n```kotlin\n" + (1..24).joinToString("\n") { "val result$it = $it + 1 // result" } + "\n```")
            4 -> ChatEntryBody.User("Screenshot $index", listOf(ChatImage("picture", "image/png", 0uL, 1024u, 768u, true)))
            5 -> ChatEntryBody.Agent("Diagram $index\n\n```mermaid\nflowchart TD\n A[Read] --> B{Valid?}\n B --> C[Render]\n B --> D[Retry]\n```\n\nThe result remains readable.")
            else -> ChatEntryBody.Agent("### Response $index\n\n" + "The implementation keeps the previous content while streaming. ".repeat(16) + "\n\n- First detail\n- Second detail\n\n| Item | Result |\n| --- | --- |\n| Change | Complete |")
        }
        ChatEntry("entry-$index", null, body)
    } }
    private val tail = ChatEntry("stream", null, ChatEntryBody.Agent("Streaming response"))
    // The native watch shares unchanged bodies and sends only changed wrappers.
    private fun freshHistory() = history.map { entry ->
        entry.copy(body = when (val body = entry.body) {
            is ChatEntryBody.User -> body.copy()
            is ChatEntryBody.Agent -> body.copy()
            is ChatEntryBody.Thinking -> body.copy()
            is ChatEntryBody.Tool -> body.copy()
            else -> body
        })
    }
    private val picture by lazy {
        ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(60, 100, 140))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bitmap.recycle()
            bytes.toByteArray()
        }
    }
    private val watch = object : ChatWatch(NoHandle) {
        private var previous = emptyMap<String, ChatEntry>()
        override suspend fun next() = updates.receive()
        override suspend fun nextUpdate(): ChatUpdate {
            val snapshot = updates.receive()
            val upserts = snapshot.entries.filter { previous[it.id] != it }
            val update = ChatUpdate(previous.isEmpty(), snapshot.entries.map { it.id }, upserts, snapshot.status, snapshot.cursor)
            previous = snapshot.entries.associateBy { it.id }
            return update
        }
        override fun image(id: String) = picture
        override fun stop() {}
        override fun close() {}
    }
    private val core = Proxy.newProxyInstance(AriesCoreInterface::class.java.classLoader, arrayOf(AriesCoreInterface::class.java)) { _, method, _ ->
        when {
            method.returnType == ChatWatch::class.java -> watch
            method.name == "toString" -> "ChatProfileCore"
            else -> error("Unexpected fake-core call: ${method.name}")
        }
    } as AriesCoreInterface
    private lateinit var chat: ChatModel
    private var streaming: Job? = null
    private var emittedUpdates = 0
    private val tracer = ChatCompositionTrace()
    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("command")) {
                "reset" -> {
                    tracer.reset()
                    emittedUpdates = 0
                    if (intent.getBooleanExtra("stream", false)) streaming = lifecycleScope.launch {
                        repeat(200) { step ->
                            val text = "Streaming response\n\n" + (0..step).joinToString(" ") { "token$it" }
                            updates.send(ChatSnapshot(freshHistory() + tail.copy(body = ChatEntryBody.Agent(text)), ChatStatus.LIVE, "$step"))
                            emittedUpdates++
                            delay(100)
                        }
                    }
                    Log.i("ChatProfile", "READY")
                }
                "dump" -> {
                    streaming?.cancel()
                    Log.i("ChatProfile", "UPDATES count=$emittedUpdates cursor=${chat.snapshot?.cursor}")
                    tracer.dump()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences("chat_reading_positions", MODE_PRIVATE).edit().clear().apply()
        // Decode input bytes before the timed workload.
        picture
        Composer.setTracer(tracer)
        chat = ChatModel(core, Host("profile", "Profile", Endpoint.Ssh("unused", 22u, "unused"), null),
            TerminalInfo("profile", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL))
        chat.start(lifecycleScope)
        updates.trySend(ChatSnapshot(history + tail, ChatStatus.LIVE, null))
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(commands, IntentFilter("$packageName.PROFILE"), RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(commands, IntentFilter("$packageName.PROFILE"))
        val appearance = ThemeSettings.from(this)
        setContent {
            AriesTheme(appearance) {
                Scaffold { padding -> Box(Modifier.padding(padding)) { ChatScreen(chat, onBack = {}) } }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(commands)
        Composer.setTracer(null)
        streaming?.cancel()
        chat.stop()
        super.onDestroy()
    }
}

/** Compiler composition tracing: counts executed bodies (skipped bodies do not emit events).
 * Inclusive durations identify expensive composition paths; counts include first composition.
 * This tracer exists only in the non-shipping profile source set.
 */
@OptIn(InternalComposeTracingApi::class)
private class ChatCompositionTrace : CompositionTracer {
    private data class Stat(var count: Long = 0, var nanos: Long = 0, var max: Long = 0)
    private val stats = linkedMapOf<String, Stat>()
    private val stack = ArrayList<Pair<String?, Long>>()
    private var enabled = false
    override fun isTraceInProgress() = enabled
    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val name = info.substringBefore(" (").takeIf { it.startsWith("io.github.shiroel.aries") }
        stack.add(name to if (name != null) System.nanoTime() else 0L)
    }
    override fun traceEventEnd() {
        if (Looper.myLooper() != Looper.getMainLooper() || stack.isEmpty()) return
        val (name, start) = stack.removeAt(stack.lastIndex)
        if (name != null) {
            val duration = System.nanoTime() - start
            val stat = stats.getOrPut(name) { Stat() }
            stat.count++; stat.nanos += duration; stat.max = maxOf(stat.max, duration)
        }
    }
    fun reset() { enabled = false; stats.clear(); stack.clear(); enabled = true }
    fun dump() {
        enabled = false
        stats.entries.sortedByDescending { it.value.nanos }.forEach { (name, stat) ->
            Log.i("ChatProfile", "$name count=${stat.count} totalMs=${stat.nanos / 1_000_000.0} maxMs=${stat.max / 1_000_000.0}")
        }
        Log.i("ChatProfile", "DONE")
    }
}
