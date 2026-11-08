package io.github.shiroel.aries

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.AttachMode
import io.github.shiroel.aries.core.AttachOptions
import io.github.shiroel.aries.core.TerminalUpdate
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.ui.AriesApp
import io.github.shiroel.aries.ui.HostsModel
import io.github.shiroel.aries.ui.Screen
import io.github.shiroel.aries.ui.TerminalInputView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Key -> echo drawn, through the real app path: the custom InputConnection,
 * the core, SSH to a Host, herdr-core, the PTY echo, back, and the Compose
 * draw. Needs a Host from `scripts/measure-terminal-latency.sh` (instrumentation
 * arg `latencyPort`, the fixture sshd's port on the dev machine, which the
 * emulator reaches at 10.0.2.2); skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class TerminalLatencyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "latency-test")

    @Test
    fun keyToDrawnEcho() {
        val port = args.getString("latencyPort")?.toIntOrNull()
        assumeTrue("no latencyPort argument", port != null)
        val keys = args.getString("latencyKeys")?.toIntOrNull() ?: 20
        dir.deleteRecursively()
        val core = AriesCore(dir.path, KeystoreVault("aries-latency-test"))
        core.setAttachMode(AttachMode.INPUT)
        val host = core.addHost("latency", Endpoint.Ssh(args.getString("latencyHost") ?: "10.0.2.2", port!!.toUShort(), "bootstrap"))
        val terminal = runBlocking {
            try {
                core.installKeyWithPassword(host.id, PASSWORD)
            } catch (e: CoreException.HostKeyUnknown) {
                core.trustHostKey(host.id, e.fingerprint)
                core.installKeyWithPassword(host.id, PASSWORD)
            }
            val conn = core.connect(host.id)
            // A cooked tty echoes every key, as a shell prompt does.
            conn.launch(LaunchRequest("sh -c 'echo ready; exec cat >/dev/null'", null, null, null))
                .also { conn.disconnect() }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var model: HostsModel
        compose.runOnUiThread {
            model = HostsModel(core, scope)
        }
        compose.setContent { MaterialTheme { AriesApp(model) } }
        compose.runOnUiThread { model.connect(model.hosts.single()) }
        compose.waitUntil(30_000) { model.screen is Screen.Browse }
        compose.runOnUiThread { model.openTerminal(terminal) }
        val viewer = (model.screen as Screen.Terminal).viewer
        compose.waitUntil(30_000) { viewer.text().contains("ready") }

        val view = findInput(compose.activity.window.decorView)!!
        val ic = compose.runOnUiThread {
            view.requestFocus()
            requireNotNull(view.onCreateInputConnection(EditorInfo()))
        }
        val root = compose.activity.window.decorView

        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        var typed = ""
        val ms = mutableListOf<Double>()
        val applied = mutableListOf<Double>()
        for (i in 0 until keys) {
            SystemClock.sleep(150) // keys apart, as a person types
            typed += alphabet[i % alphabet.length]
            val want = typed
            val drawn = CountDownLatch(1)
            var t1 = 0L
            val listener = ViewTreeObserver.OnDrawListener {
                if (t1 == 0L && viewer.text().lines().any { it.trimEnd() == want }) {
                    t1 = SystemClock.elapsedRealtimeNanos()
                    drawn.countDown()
                }
            }
            var t0 = 0L
            compose.runOnUiThread {
                root.viewTreeObserver.addOnDrawListener(listener)
                t0 = SystemClock.elapsedRealtimeNanos()
                ic.commitText(want.takeLast(1), 1)
            }
            // When the frame reached the viewer (before any recomposition or draw).
            var tApplied = 0L
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (tApplied == 0L && SystemClock.elapsedRealtime() < deadline) {
                val shown = try {
                    viewer.text().lines().any { it.trimEnd() == want }
                } catch (e: RuntimeException) {
                    false // the UI thread was mid-update
                }
                if (shown) tApplied = SystemClock.elapsedRealtimeNanos() else Thread.sleep(0, 250_000)
            }
            assertTrue(
                "echo of '$want' not drawn (applied: ${tApplied != 0L}); ${viewer.cols}x${viewer.rows}: ${viewer.text()}",
                drawn.await(10, TimeUnit.SECONDS),
            )
            compose.runOnUiThread { root.viewTreeObserver.removeOnDrawListener(listener) }
            ms += (t1 - t0) / 1e6
            applied += (tApplied - t0) / 1e6
        }
        compose.runOnUiThread { model.closeTerminal() }
        scope.cancel()

        // The same keys without the UI: core + SSH + Host only.
        val coreMs = runBlocking(Dispatchers.IO) {
            val conn = core.connect(host.id)
            val echo = conn.launch(LaunchRequest("sh -c 'echo ready; exec cat >/dev/null'", null, null, null))
            val view = conn.attachTerminal(echo.address, AttachOptions(observe = false, cols = 80u, rows = 24u, history = 0u, resumed = false))
            val screen = mutableMapOf<Int, String>()
            suspend fun until(pred: () -> Boolean) {
                while (!pred()) {
                    val u = view.next()
                    if (u is TerminalUpdate.Screen) {
                        if (u.frame.full) screen.clear()
                        for (l in u.frame.lines) {
                            val sb = StringBuilder()
                            for (r in l.runs) {
                                while (sb.length < r.column.toInt()) sb.append(' ')
                                sb.append(r.text)
                            }
                            screen[l.row.toInt()] = sb.toString().trimEnd()
                        }
                    }
                }
            }
            until { screen.values.any { it.contains("ready") } }
            var line = ""
            val out = mutableListOf<Double>()
            for (i in 0 until keys) {
                SystemClock.sleep(150)
                line += alphabet[i % alphabet.length]
                val want = line
                val t0 = SystemClock.elapsedRealtimeNanos()
                view.sendText(want.takeLast(1))
                until { screen.values.any { it == want } }
                out += (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            }
            view.detach()
            view.close()
            conn.disconnect()
            out
        }
        dir.deleteRecursively()

        Log.i("AriesLatency", "phone grid while typing: ${viewer.cols}x${viewer.rows}")
        report("key->drawn", ms)
        report("key->viewer (frame applied, before draw)", applied)
        report("key->core frame (no UI)", coreMs)
    }

    private fun report(what: String, ms: List<Double>) {
        val sorted = ms.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        val line = "$what latency over ${ms.size} keys: mean %.1f ms, p50 %.1f, p95 %.1f, max %.1f; raw %s".format(
            ms.average(), pct(0.5), pct(0.95), sorted.last(), ms.joinToString(",") { "%.1f".format(it) },
        )
        Log.i("AriesLatency", line)
        println(line)
    }

    private fun findInput(v: View): TerminalInputView? = when (v) {
        is TerminalInputView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findInput(v.getChildAt(it)) }
        else -> null
    }

    companion object {
        /** The fixture sshd's `bootstrap` password. */
        const val PASSWORD = "aries-test-password"
    }
}
