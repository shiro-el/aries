package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a Host's line stands. */
sealed interface LinkState {
    data object Up : LinkState

    /** The line dropped; the app is connecting again on its own. */
    data object Reconnecting : LinkState

    /** Connecting again needs the user first ([why]): a changed Host key, a rejected key, ... */
    data class Down(val error: CoreException) : LinkState {
        val why: Prompt.Problem get() = problemFor(error)
    }
}

/** Waits between reconnect attempts; the last one repeats. A network change or the app coming back retries at once. */
val RETRY_DELAYS_MS = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

/** How long a line may take to answer when the app comes back before it counts as dead. */
const val PROBE_TIMEOUT_MS = 5_000L

/**
 * One Host's connection that comes back after the line drops (Wi-Fi to LTE,
 * airplane mode, the app back from the background).
 *
 * Extension point: everything that streams from a Host runs inside [keep].
 * When the line drops, the link connects again and runs each block again on
 * the new connection with `resumed = true`. A block keeps its own position
 * across runs: the open Terminal re-attaches with `AttachOptions.resumed`, the
 * Agent list starts from the new snapshot, and Chat (ticket 37) and its cache
 * (ticket 44) continue from the cursor they last saw.
 */
class HostLink(
    val host: Host,
    first: HostConnection?,
    private val scope: CoroutineScope,
    private val connect: suspend () -> HostConnection,
    private val onDown: (CoreException) -> Unit = {},
    private val onSettings: (CoreException?) -> Unit = {},
    private val retryDelays: List<Long> = RETRY_DELAYS_MS,
) {
    var state by mutableStateOf<LinkState>(if (first == null) LinkState.Reconnecting else LinkState.Up)
        private set

    private val current = MutableStateFlow<HostConnection?>(first)
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private var reconnecting: Job? = null
    private var closed = false

    /** The live connection; `null` while reconnecting. */
    val connection: HostConnection? get() = current.value

    /**
     * Runs [block] on the live connection, and again on the next one each
     * time it fails with [CoreException.Closed] (the line dropped). Other
     * failures end it. While the link reconnects it waits.
     */
    suspend fun <T> keep(block: suspend (conn: HostConnection, resumed: Boolean) -> T): T {
        // The owner registers this link and its screen before any failure
        // callback can run (including an immediately rejected first connect).
        if (!closed && current.value == null && reconnecting == null) {
            reconnecting = scope.launch { reconnect() }
        }
        var resumed = false
        while (true) {
            if (closed) throw CancellationException("Host link closed")
            val conn = current.filterNotNull().first()
            try {
                return block(conn, resumed)
            } catch (e: CoreException.Closed) {
                lost(conn)
            }
            resumed = true
        }
    }

    /** [conn] stopped working: drop it and connect again. A connection already replaced is ignored. */
    fun lost(conn: HostConnection) {
        if (closed || current.value !== conn) return
        current.value = null
        state = LinkState.Reconnecting
        scope.launch { conn.disconnect() }
        reconnecting = scope.launch { reconnect() }
    }

    /** Explicit user retry also resumes a permanent failure after it has been fixed. */
    fun retryNow() {
        if (closed) return
        if (state is LinkState.Down) {
            state = LinkState.Reconnecting
            reconnecting = scope.launch { reconnect() }
        } else poke.trySend(Unit)
    }

    fun settings() = onSettings((state as? LinkState.Down)?.error)

    /** The phone's network changed: the old line is likely dead, so replace it now (or retry now). */
    fun networkChanged() {
        current.value?.let(::lost) ?: poke.trySend(Unit)
    }

    /** The app is back in the foreground: make sure the line still answers, or retry now. */
    fun check() {
        val conn = current.value ?: run {
            poke.trySend(Unit)
            return
        }
        scope.launch {
            val alive = try {
                withTimeoutOrNull(PROBE_TIMEOUT_MS) { conn.terminals() } != null
            } catch (e: CoreException) {
                false
            }
            if (!alive) lost(conn)
        }
    }

    /** Stops for good (the user left the Host) and disconnects. */
    fun close() {
        closed = true
        reconnecting?.cancel()
        val conn = current.value
        current.value = null
        conn?.let { scope.launch { it.disconnect() } }
    }

    private suspend fun reconnect() {
        poke.tryReceive()
        var attempt = 0
        while (!closed) {
            try {
                val conn = connect()
                if (closed) {
                    conn.disconnect()
                    return
                }
                state = LinkState.Up
                current.value = conn
                return
            } catch (e: CoreException) {
                // Keep the typed error. Protocol, auth, trust, permission and key
                // unlock failures need an explicit user action, never a timer.
                if (e !is CoreException.Unreachable && e !is CoreException.Closed) {
                    state = LinkState.Down(e)
                    onDown(e)
                    return
                }
            }
            withTimeoutOrNull(retryDelays[minOf(attempt, retryDelays.lastIndex)]) { poke.receive() }
            attempt++
        }
    }
}
