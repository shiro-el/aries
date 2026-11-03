package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.NoHandle
import io.github.shiroel.aries.core.PublicKeyInfo
import io.github.shiroel.aries.core.StructureListener
import io.github.shiroel.aries.core.StructureWatch
import io.github.shiroel.aries.core.KeyInstall
import io.github.shiroel.aries.core.QrMatrix
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalWatch
import kotlinx.coroutines.channels.Channel

/** In-memory stand-in for the Rust core, for UI tests. */
class FakeCore : AriesCoreInterface {
    /** When set, `connect` asks the user to trust this fingerprint first. */
    var requireTrust: String? = null

    /** When set, `connect` fails with it. */
    var failWith: CoreException? = null

    var terminals: List<TerminalInfo> = emptyList()

    /** Later Terminal lists a live watch delivers, in order (see [push]). */
    val updates = Channel<List<TerminalInfo>>(Channel.UNLIMITED)

    /** Sends a changed Terminal list to the open watch. */
    fun push(list: List<TerminalInfo>) {
        updates.trySend(list)
    }
    /** Per-Host Terminal lists (by Host id), for tests with several Hosts; others get [terminals]. */
    val terminalsOf = mutableMapOf<String, List<TerminalInfo>>()

    /** Per-Host failures of `connect`. */
    val failFor = mutableMapOf<String, CoreException>()

    /** Shared label writes through any connection: `"<host id> <address> seen=N"` or `pinned=true|false`. */
    val labelWrites = mutableListOf<String>()

    private val hostUpdates = mutableMapOf<String, Channel<List<TerminalInfo>>>()

    private fun updatesOf(hostId: String) = hostUpdates.getOrPut(hostId) { Channel(Channel.UNLIMITED) }

    /** [hostId]'s Terminal list changed (on the Host or another client). */
    fun push(hostId: String, list: List<TerminalInfo>) {
        terminalsOf[hostId] = list
        updatesOf(hostId).trySend(list)
    }

    /** What the fake Host does on a label write: change its Terminal and tell its watchers. */
    internal fun label(hostId: String, address: String, write: String, change: (TerminalInfo) -> TerminalInfo): TerminalInfo {
        labelWrites += "$hostId $address $write"
        val list = terminalsOf[hostId] ?: throw CoreException.UnknownHost(hostId)
        val t = list.find { it.address == address } ?: throw CoreException.Closed()
        val changed = change(t)
        push(hostId, list.map { if (it.address == address) changed else it })
        return changed
    }

    /** What `structure`/`watchStructure` deliver; default: [terminals], all unplaced. */
    var structure: HostStructure? = null

    /** The last connection `connect` returned. */
    var connection: FakeConnection? = null
        private set
    /** When set, `installKeyWithPassword` fails with it. */
    var installFailWith: CoreException? = null

    /** Passwords `installKeyWithPassword` was called with, per Host id. */
    val passwordsTried = mutableListOf<Pair<String, String>>()

    /** Host ids whose (fake) `authorized_keys` already holds the key. */
    private val installed = mutableSetOf<String>()

    private var key: PublicKeyInfo? = null
    private val hosts = mutableListOf<Host>()

    override fun publicKey(): PublicKeyInfo? = key

    override fun ensureKey(): PublicKeyInfo =
        key ?: PublicKeyInfo(PUBLIC_KEY, "SHA256:fake").also { key = it }

    override fun publicKeyQr(): QrMatrix? =
        key?.let { QrMatrix(2u, listOf(true, false, false, true)) }

    override suspend fun installKeyWithPassword(hostId: String, password: String): KeyInstall {
        val host = hosts.find { it.id == hostId } ?: throw CoreException.UnknownHost(hostId)
        requireTrust?.let { if (host.hostKey != it) throw CoreException.HostKeyUnknown(it) }
        passwordsTried += hostId to password
        installFailWith?.let { throw it }
        ensureKey()
        return if (installed.add(hostId)) KeyInstall.ADDED else KeyInstall.ALREADY_PRESENT
    }

    override fun hosts(): List<Host> = hosts.toList()

    override fun addHost(name: String, endpoint: Endpoint): Host =
        Host("h${hosts.size + 1}", name, endpoint, null).also { hosts += it }

    override fun trustHostKey(hostId: String, fingerprint: String) {
        val i = hosts.indexOfFirst { it.id == hostId }
        if (i < 0) throw CoreException.UnknownHost(hostId)
        hosts[i] = hosts[i].copy(hostKey = fingerprint)
    }

    override fun removeHost(hostId: String) {
        if (!hosts.removeIf { it.id == hostId }) throw CoreException.UnknownHost(hostId)
    }

    override suspend fun connect(hostId: String): HostConnection {
        failWith?.let { throw it }
        val host = hosts.find { it.id == hostId } ?: throw CoreException.UnknownHost(hostId)
        requireTrust?.let { if (host.hostKey != it) throw CoreException.HostKeyUnknown(it) }
        failFor[hostId]?.let { throw it }
        terminalsOf[hostId]?.let { list ->
            return FakeConnection(list, structure ?: HostStructure(emptyList(), list, null), updatesOf(hostId), hostId, this)
                .also { connection = it }
        }
        return FakeConnection(terminals, structure ?: HostStructure(emptyList(), terminals, null), updates)
            .also { connection = it }
    }

    companion object {
        const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeFakeFakeFakeFakeFakeFakeFakeFakeFake aries"
    }
}

/** A connection that answers from memory; never touches the native handle. */
class FakeConnection(
    private val list: List<TerminalInfo>,
    private var current: HostStructure,
    private val updates: Channel<List<TerminalInfo>> = Channel(),
    private val hostId: String = "",
    private val core: FakeCore? = null,
) : HostConnection(NoHandle) {
    override suspend fun markSeen(address: String, turn: ULong): TerminalInfo =
        core!!.label(hostId, address, "seen=$turn") {
            if (it.agentStatus == AgentStatus.Done && turn >= it.turn) it.copy(agentStatus = AgentStatus.Idle) else it
        }

    override suspend fun setPinned(address: String, pinned: Boolean): TerminalInfo =
        core!!.label(hostId, address, "pinned=$pinned") { it.copy(pinned = pinned) }

    /** Core API methods called on this connection, in order. */
    val calls = mutableListOf<String>()
    var disconnected = false
        private set
    var watchStopped = false
        private set
    private var listener: StructureListener? = null

    override fun coreVersion(): String = "0.1.17"

    override suspend fun terminals(): List<TerminalInfo> = list.also { calls += "terminals" }

    override suspend fun watchTerminals(): TerminalWatch = FakeWatch(list, updates)
    override suspend fun structure(): HostStructure = current.also { calls += "structure" }

    override suspend fun watchStructure(listener: StructureListener): StructureWatch {
        calls += "watchStructure"
        this.listener = listener
        listener.onStructure(current)
        return object : StructureWatch(NoHandle) {
            override fun stop() {
                watchStopped = true
                this@FakeConnection.listener = null
            }

            override fun close() {}
        }
    }

    /** The desktop changed the structure. */
    fun push(structure: HostStructure) {
        current = structure
        listener?.onStructure(structure)
    }

    override suspend fun disconnect() {
        disconnected = true
    }

    override fun close() {}
}

/** First the connection's list, then whatever the test pushes. */
class FakeWatch(
    private val first: List<TerminalInfo>,
    private val updates: Channel<List<TerminalInfo>>,
) : TerminalWatch(NoHandle) {
    private var started = false

    override suspend fun next(): List<TerminalInfo> {
        if (!started) {
            started = true
            return first
        }
        return updates.receive()
    }

    override fun stop() {}

    override fun close() {}
}
