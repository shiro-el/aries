package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.JumpHost
import io.github.shiroel.aries.core.KeyInfo
import io.github.shiroel.aries.core.NoHandle
import io.github.shiroel.aries.core.PublicKeyInfo
import io.github.shiroel.aries.core.TerminalInfo

/** In-memory stand-in for the Rust core, for UI tests. */
class FakeCore : AriesCoreInterface {
    /** When set, `connect` asks the user to trust this fingerprint first. */
    var requireTrust: String? = null

    /** When set, `connect` fails with it. */
    var failWith: CoreException? = null

    var terminals: List<TerminalInfo> = emptyList()

    /** When set, `connect` asks to trust this fingerprint for each jump host first. */
    var requireJumpTrust: String? = null

    private var key: PublicKeyInfo? = null
    private val hosts = mutableListOf<Host>()
    private val extraKeys = mutableListOf<KeyInfo>()
    private val jumps = mutableMapOf<String, List<JumpHost>>()
    private val hostKeys = mutableMapOf<String, String?>()

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

    override fun createKey(label: String): KeyInfo =
        KeyInfo("k${extraKeys.size + 1}", label, PUBLIC_KEY, "SHA256:fake-$label").also { extraKeys += it }

    override fun keys(): List<KeyInfo> =
        listOfNotNull(key?.let { KeyInfo("default", "", it.openssh, it.fingerprint) }) + extraKeys

    override fun jumpHosts(hostId: String): List<JumpHost> = jumps[host(hostId).id].orEmpty()

    override fun setJumpHosts(hostId: String, jumps: List<JumpHost>) {
        val old = jumpHosts(hostId)
        this.jumps[hostId] = jumps.map { j ->
            j.copy(hostKey = old.find { it.address == j.address && it.port == j.port }?.hostKey)
        }
    }

    override fun trustJumpHostKey(hostId: String, hop: UInt, fingerprint: String) {
        val list = jumpHosts(hostId).toMutableList()
        if (hop.toInt() !in list.indices) throw CoreException.UnknownJumpHost(hop)
        list[hop.toInt()] = list[hop.toInt()].copy(hostKey = fingerprint)
        jumps[hostId] = list
    }

    override fun hostSshKey(hostId: String): String? = hostKeys[host(hostId).id]

    override fun setHostSshKey(hostId: String, keyId: String?) {
        hostKeys[host(hostId).id] = keyId
    }

    private fun host(id: String) = hosts.find { it.id == id } ?: throw CoreException.UnknownHost(id)

    override fun removeHost(hostId: String) {
        if (!hosts.removeIf { it.id == hostId }) throw CoreException.UnknownHost(hostId)
    }

    override suspend fun connect(hostId: String): HostConnection {
        failWith?.let { throw it }
        val host = hosts.find { it.id == hostId } ?: throw CoreException.UnknownHost(hostId)
        requireJumpTrust?.let { fp ->
            jumpHosts(hostId).forEachIndexed { i, j ->
                if (j.hostKey != fp) throw CoreException.JumpHostKeyUnknown(i.toUInt(), j.address, fp)
            }
        }
        requireTrust?.let { if (host.hostKey != it) throw CoreException.HostKeyUnknown(it) }
        return FakeConnection(terminals)
    }

    companion object {
        const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeFakeFakeFakeFakeFakeFakeFakeFakeFake aries"
    }
}

/** A connection that answers from memory; never touches the native handle. */
class FakeConnection(private val list: List<TerminalInfo>) : HostConnection(NoHandle) {
    override fun coreVersion(): String = "0.1.17"

    override suspend fun terminals(): List<TerminalInfo> = list

    override suspend fun disconnect() {}

    override fun close() {}
}
