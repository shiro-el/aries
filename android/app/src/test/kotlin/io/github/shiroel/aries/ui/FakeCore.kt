package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.NoHandle
import io.github.shiroel.aries.core.PublicKeyInfo
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

    private var key: PublicKeyInfo? = null
    private val hosts = mutableListOf<Host>()

    override fun publicKey(): PublicKeyInfo? = key

    override fun ensureKey(): PublicKeyInfo =
        key ?: PublicKeyInfo(PUBLIC_KEY, "SHA256:fake").also { key = it }

    override fun hosts(): List<Host> = hosts.toList()

    override fun addHost(name: String, endpoint: Endpoint): Host =
        Host("h${hosts.size + 1}", name, endpoint, null).also { hosts += it }

    override fun trustHostKey(hostId: String, fingerprint: String) {
        val i = hosts.indexOfFirst { it.id == hostId }
        if (i < 0) throw CoreException.UnknownHost(hostId)
        hosts[i] = hosts[i].copy(hostKey = fingerprint)
    }

    override suspend fun connect(hostId: String): HostConnection {
        failWith?.let { throw it }
        val host = hosts.find { it.id == hostId } ?: throw CoreException.UnknownHost(hostId)
        requireTrust?.let { if (host.hostKey != it) throw CoreException.HostKeyUnknown(it) }
        return FakeConnection(terminals, updates)
    }

    companion object {
        const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeFakeFakeFakeFakeFakeFakeFakeFakeFake aries"
    }
}

/** A connection that answers from memory; never touches the native handle. */
class FakeConnection(
    private val list: List<TerminalInfo>,
    private val updates: Channel<List<TerminalInfo>> = Channel(),
) : HostConnection(NoHandle) {
    override fun coreVersion(): String = "0.1.17"

    override suspend fun terminals(): List<TerminalInfo> = list

    override suspend fun watchTerminals(): TerminalWatch = FakeWatch(list, updates)

    override suspend fun disconnect() {}

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
