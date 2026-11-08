package io.github.shiroel.aries

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.ui.AriesApp
import io.github.shiroel.aries.ui.HostsModel
import io.github.shiroel.aries.ui.Screen
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame times while watching a fullscreen agent TUI (alternate screen, box
 * drawing, Hangul), through the real app path. Needs the Host and fixture
 * program from `scripts/measure-terminal-jank.sh`; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class TerminalJankTest {
    // No Compose test rule: its continuation interceptor resumes core callbacks
    // on the Rust thread, which the real app never does.
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "jank-test")

    @Test
    fun watchingAFullscreenAgent() {
        val port = args.getString("jankPort")?.toIntOrNull()
        assumeTrue("no jankPort argument", port != null)
        val seconds = args.getString("jankSeconds")?.toLongOrNull() ?: 8
        dir.deleteRecursively()
        val core = AriesCore(dir.path, KeystoreVault("aries-jank-test"))
        val host = core.addHost("jank", Endpoint.Ssh(args.getString("jankHost") ?: "10.0.2.2", port!!.toUShort(), "bootstrap"))
        runBlocking {
            try {
                core.installKeyWithPassword(host.id, TerminalLatencyTest.PASSWORD)
            } catch (e: CoreException.HostKeyUnknown) {
                core.trustHostKey(host.id, e.fingerprint)
                core.installKeyWithPassword(host.id, TerminalLatencyTest.PASSWORD)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var model: HostsModel
        lateinit var window: Window
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity {
            model = HostsModel(core, scope)
            it.setContent { MaterialTheme { AriesApp(model) } }
            window = it.window
            model.connect(model.hosts.single())
        }
        until { model.screen is Screen.Browse }

        // spinner: one line changes 10 times a second (an agent working).
        // repaint: the whole screen changes 20 times a second (scrolling it).
        for (mode in listOf("spinner", "repaint")) {
            val terminal = runBlocking {
                val conn = core.connect(host.id)
                conn.launch(LaunchRequest("sh /home/bootstrap/fullscreen.sh $mode", null, null, null)).also { conn.disconnect() }
            }
            ui { model.openTerminal(terminal) }
            val viewer = ui { (model.screen as Screen.Terminal).viewer }
            until { ui { viewer.text().contains("AriesJank") } }
            SystemClock.sleep(1_000)
            measure(window, "$mode ${viewer.cols}x${viewer.rows}", seconds) { viewer.version }
            // For a look at what was drawn (the script pulls it).
            shell("screencap -p /sdcard/Download/aries-jank-$mode.png")
            if (mode == "repaint") {
                measure(window, "repaint+drag ${viewer.cols}x${viewer.rows}", seconds, drag = true) { viewer.version }
            }
            ui { model.closeTerminal() }
            SystemClock.sleep(500)
        }
        scope.cancel()
        scenario.close()
        dir.deleteRecursively()
    }

    private fun measure(window: Window, what: String, seconds: Long, drag: Boolean = false, version: () -> Int) {
        val totals = Collections.synchronizedList(mutableListOf<Double>())
        // Where a frame's time goes: composition and input (animation), layout,
        // recording the draw (UI thread), and issuing it to the GPU (RenderThread).
        val parts = listOf(
            "anim" to FrameMetrics.ANIMATION_DURATION, "input" to FrameMetrics.INPUT_HANDLING_DURATION,
            "layout" to FrameMetrics.LAYOUT_MEASURE_DURATION, "draw" to FrameMetrics.DRAW_DURATION,
            "gpu" to FrameMetrics.COMMAND_ISSUE_DURATION,
        )
        val split = parts.associate { it.first to Collections.synchronizedList(mutableListOf<Double>()) }
        val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                totals += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
                for ((name, id) in parts) split.getValue(name) += metrics.getMetric(id) / 1e6
            }
        }
        ui { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val v0 = ui { version() }
        val t0 = SystemClock.elapsedRealtime()
        val end = t0 + seconds * 1000
        var down = true
        while (SystemClock.elapsedRealtime() < end) {
            if (drag) {
                val (from, to) = if (down) 700 to 1700 else 1700 to 700
                shell("input swipe 540 $from 540 $to 400")
                down = !down
            } else {
                SystemClock.sleep(100)
            }
        }
        val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val frames = ui { version() } - v0
        ui { window.removeOnFrameMetricsAvailableListener(listener) }
        SystemClock.sleep(200)
        thread.quitSafely()
        val sorted = synchronized(totals) { totals.sorted() }
        fun pct(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
        val janky = sorted.count { it > 16.7 } * 100.0 / sorted.size.coerceAtLeast(1)
        val medians = split.entries.joinToString(" ") { (name, ms) ->
            val m = synchronized(ms) { ms.sorted() }
            "$name %.1f".format(if (m.isEmpty()) 0.0 else m[m.size / 2])
        }
        val line = "$what: %d drawn frames in %.1fs, terminal frames applied %.1f/s; frame ms p50 %.1f p90 %.1f p99 %.1f max %.1f; >16.7ms %.0f%%; p50 parts: %s".format(
            sorted.size, elapsed, frames / elapsed, pct(0.5), pct(0.9), pct(0.99), sorted.lastOrNull() ?: 0.0, janky, medians,
        )
        Log.i("AriesJank", line)
        println(line)
    }

    private fun <T> ui(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun until(pred: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!pred()) {
            check(SystemClock.elapsedRealtime() < deadline) { "timed out" }
            SystemClock.sleep(50)
        }
    }

    /** Waits for a shell command (`input swipe` returns once the gesture is injected). */
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
}
