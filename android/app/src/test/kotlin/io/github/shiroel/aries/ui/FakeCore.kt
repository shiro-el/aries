package io.github.shiroel.aries.ui

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
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.QrMatrix
import io.github.shiroel.aries.core.TerminalInfo

/** In-memory stand-in for the Rust core, for UI tests. */
class FakeCore : AriesCoreInterface {
    /** When set, `connect` asks the user to trust this fingerprint first. */
    var requireTrust: String? = null

    /** When set, `connect` fails with it. */
    var failWith: CoreException? = null

    var terminals: List<TerminalInfo> = emptyList()

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
        return FakeConnection(terminals, structure ?: HostStructure(emptyList(), terminals, null))
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
) : HostConnection(NoHandle) {
    /** Core API methods called on this connection, in order. */
    val calls = mutableListOf<String>()
    var disconnected = false
        private set
    var watchStopped = false
        private set
    private var listener: StructureListener? = null

    override fun coreVersion(): String = "0.1.17"

    override suspend fun terminals(): List<TerminalInfo> = list.also { calls += "terminals" }

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

    /** What `launch` was asked to start, in order. */
    val launched = mutableListOf<LaunchRequest>()

    /** When set, `launch` fails with it. */
    var launchFailWith: CoreException? = null

    var recentCwds: List<String> = emptyList()

    override fun recentCwds(): List<String> = recentCwds

    /** Like herdr-core: a new live Terminal, shown in its labelled Tab (or unplaced). */
    override suspend fun launch(request: LaunchRequest): TerminalInfo {
        calls += "launch"
        launchFailWith?.let { throw it }
        launched += request
        val t = TerminalInfo("t${100 + launched.size}", null, "", request.command.split(" "), request.cwd, "live")
        val pane = PaneInfo(t.address, t.address, t)
        val placed = current.spaces.map { s ->
            if (s.id != request.spaceId) s
            else s.copy(tabs = s.tabs.map { tab -> if (tab.id == request.tabId) tab.copy(panes = tab.panes + pane) else tab })
        }
        val inTab = placed != current.spaces
        push(current.copy(spaces = placed, unplaced = if (inTab) current.unplaced else current.unplaced + t))
        return t
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
