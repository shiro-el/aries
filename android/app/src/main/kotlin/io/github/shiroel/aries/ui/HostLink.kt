package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformLatest
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

/** Configuration failures wait for explicit action; never infer this from message text. */
fun CoreException.retryableConnection(): Boolean = this is CoreException.Unreachable || this is CoreException.Closed

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
    private var paused = false
    private var probing: Job? = null

    /** The live connection; `null` while reconnecting. */
    val connection: HostConnection? get() = current.value

    private val finishingSends = mutableMapOf<HostConnection, MutableSet<Job>>()

    /** Finish explicitly submitted input on its original line; never reconnect or retry it. */
    internal fun finishBeforeDisconnect(conn: HostConnection, send: suspend () -> Unit): Job {
        val job = scope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            // A lifecycle handoff gets one existing RPC timeout, not an ongoing service.
            withTimeoutOrNull(30_000L) { send() }
        }
        finishingSends.getOrPut(conn) { mutableSetOf() }.add(job)
        job.invokeOnCompletion {
            finishingSends[conn]?.let { jobs ->
                jobs.remove(job)
                if (jobs.isEmpty()) finishingSends.remove(conn)
            }
        }
        return job
    }

    private fun disconnectAfterSends(conn: HostConnection) {
        scope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            finishingSends[conn]?.toList()?.joinAll()
            conn.disconnect()
        }
    }

    /** Waits for foreground reconnection without replaying the caller's operation. */
    suspend fun awaitConnection(): HostConnection = withTimeoutOrNull(30_000L) {
        val (state, connection) = snapshotFlow { state to connection }.first { (state, connection) ->
            state is LinkState.Down || (state == LinkState.Up && connection != null)
        }
        if (state is LinkState.Down) throw state.error
        connection
    } ?: throw CoreException.Closed()

    /**
     * Runs [block] on the live connection, and again on the next one each
     * time it fails with [CoreException.Closed] (the line dropped). Other
     * failures end it. While the link reconnects it waits.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> keep(block: suspend (conn: HostConnection, resumed: Boolean) -> T): T {
        // The owner registers this link and its screen before any failure
        // callback can run (including an immediately rejected first connect).
        if (!closed && !paused && current.value == null && reconnecting == null) {
            reconnecting = scope.launch { reconnect() }
        }
        var resumed = false
        if (closed) throw CancellationException("Host link closed")
        return current.transformLatest { conn ->
            if (conn == null) return@transformLatest
            val again = resumed
            resumed = true

            try {
                emit(block(conn, again))
            } catch (e: CoreException.Closed) {
                lost(conn)
            }
        }.first()
    }

    /** [conn] stopped working: drop it and connect again. A connection already replaced is ignored. */
    fun lost(conn: HostConnection) {
        if (closed || current.value !== conn) return
        current.value = null
        state = LinkState.Reconnecting
        scope.launch(NonCancellable) { conn.disconnect() }
        if (!paused) reconnecting = scope.launch { reconnect() }
    }

    /** Explicit user retry also resumes a permanent failure after it has been fixed. */
    fun retryNow() {
        if (closed || paused) return
        if (state is LinkState.Down) {
            state = LinkState.Reconnecting
            reconnecting = scope.launch { reconnect() }
        } else poke.trySend(Unit)
    }

    fun settings(error: CoreException? = (state as? LinkState.Down)?.error) = onSettings(error)

    /** The phone's network changed: the old line is likely dead, so replace it now (or retry now). */
    fun networkChanged() {
        if (closed || paused) return
        current.value?.let(::lost) ?: poke.trySend(Unit)
    }

    /** The app is back in the foreground: make sure the line still answers, or retry now. */
    fun check() {
        if (closed || paused) return
        val conn = current.value ?: run {
            poke.trySend(Unit)
            return
        }
        probing?.cancel()
        probing = scope.launch {
            val alive = try {
                withTimeoutOrNull(PROBE_TIMEOUT_MS) { conn.terminals() } != null
            } catch (e: CoreException) {
                false
            }
            if (!alive) lost(conn)
        }
    }

    /** A brief background visit keeps live streams but must not open another SSH connection. */
    fun suspendRetries() {
        if (closed) return
        paused = true
        probing?.cancel()
        reconnecting?.cancel()
    }

    /** Releases screen streams and retries while retaining their models. */
    fun pause() {
        if (closed) return
        suspendRetries()
        val conn = current.value
        current.value = null
        if (state !is LinkState.Down) state = LinkState.Reconnecting
        conn?.let(::disconnectAfterSends)
    }

    fun resume() {
        if (closed || !paused) return
        paused = false
        if (current.value == null && state !is LinkState.Down) reconnecting = scope.launch { reconnect() }
    }

    /** Stops for good (the user left the Host) and disconnects. */
    fun close() {
        closed = true
        probing?.cancel()
        reconnecting?.cancel()
        val conn = current.value
        current.value = null
        // ViewModel clearing cancels the owner scope before onCleared releases its links.
        conn?.let(::disconnectAfterSends)
    }

    private suspend fun reconnect() {
        poke.tryReceive()
        var attempt = 0
        while (!closed && !paused) {

            try {
                val conn = connect()
                if (closed || paused) {
                    conn.disconnect()
                    return
                }
                state = LinkState.Up
                current.value = conn
                return
            } catch (e: CoreException) {
                // Keep the typed error. Protocol, auth, trust, permission and key
                // unlock failures need an explicit user action, never a timer.
                if (!e.retryableConnection()) {
                    state = LinkState.Down(e)
                    onDown(e)
                    return
                }
            }
            val woken = withTimeoutOrNull(retryDelays[minOf(attempt, retryDelays.lastIndex)]) { poke.receive() }
            attempt = if (woken != null) 0 else attempt + 1
        }
    }
}
