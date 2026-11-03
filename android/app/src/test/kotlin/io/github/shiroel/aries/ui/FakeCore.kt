package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.BeaconCheck
import io.github.shiroel.aries.core.AttachOptions
import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.BeaconInstall
import io.github.shiroel.aries.core.BeaconRelease
import io.github.shiroel.aries.core.BeaconSource
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.BeaconUpdate
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatWatch
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.KeyInstall
import io.github.shiroel.aries.core.Linger
import io.github.shiroel.aries.core.JumpHost
import io.github.shiroel.aries.core.KeyInfo
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.NoHandle
import io.github.shiroel.aries.core.PublicKeyInfo
import io.github.shiroel.aries.core.StructureListener
import io.github.shiroel.aries.core.StructureWatch
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.core.PaneInfo
import io.github.shiroel.aries.core.QrMatrix
import io.github.shiroel.aries.core.SavedLaunch
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import io.github.shiroel.aries.core.TerminalView
import io.github.shiroel.aries.core.TerminalWatch
import kotlinx.coroutines.channels.Channel

/** In-memory stand-in for the Rust core, for UI tests. */
class FakeCore : AriesCoreInterface {
    /** When set, `connect` asks the user to trust this fingerprint first. */
    var requireTrust: String? = null

    /** When set, `connect` fails with it. */
    var failWith: CoreException? = null

    var terminals: List<TerminalInfo> = emptyList()

    /** When set, `connect` asks to trust this fingerprint for each jump host first. */
    var requireJumpTrust: String? = null
    /** Later Terminal lists a live watch delivers, in order (see [push]). */
    val updates = Channel<List<TerminalInfo>>(Channel.UNLIMITED)

    /** Sends a changed Terminal list to the open watch. */
    fun push(list: List<TerminalInfo>) {
        updates.trySend(list)
    }
    /** Per-Host Terminal lists (by Host id), for tests with several Hosts; others get [terminals]. */
    val terminalsOf = mutableMapOf<String, List<TerminalInfo>>()

    /** The latest connection to each Host in [terminalsOf], by Host id. */
    val connectionsOf = mutableMapOf<String, FakeConnection>()

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
    private val extraKeys = mutableListOf<KeyInfo>()
    private val jumps = mutableMapOf<String, List<JumpHost>>()
    private val hostKeys = mutableMapOf<String, String?>()

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

    /** When set, `setKeyProtection` fails with it and the policy stays. */
    var failProtectionWith: CoreException? = null

    private var protection = KeyProtection.PLAIN

    override fun keyProtection(): KeyProtection? = key?.let { protection }

    override fun setKeyProtection(protection: KeyProtection) {
        if (key == null) throw CoreException.NoKey()
        failProtectionWith?.let { throw it }
        this.protection = protection
    }

    override fun canAnswerInBackground(hostId: String): Boolean {
        val host = hosts.find { it.id == hostId } ?: throw CoreException.UnknownHost(hostId)
        return host.endpoint is Endpoint.Local || keyProtection() == KeyProtection.PLAIN
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
        failFor[hostId]?.let { throw it }
        terminalsOf[hostId]?.let { list ->
            return FakeConnection(list, structure ?: HostStructure(emptyList(), list, null), updatesOf(hostId), hostId, this)
                .also { connection = it; connectionsOf[hostId] = it }
        }
        return FakeConnection(terminals, structure ?: HostStructure(emptyList(), terminals, null), updates, saved = savedLaunches)
            .also { connection = it }
    }

    /** Saved launch configs, kept across connections like the core keeps them per Host. */
    val savedLaunches = mutableListOf<SavedLaunch>()

    /** What `beaconStatus` reports; `installBeacon` / `uninstallBeacon` change it. */
    var beacon: BeaconState = BeaconState.NotInstalled

    /** How `installBeacon` ends: its linger result, or this error. */
    var beaconLinger: Linger = Linger.Enabled
    var beaconFailWith: CoreException? = null
    var beaconOs = "linux"
    var autoUpdate = true
    var installs = 0

    override fun beaconRelease(): BeaconRelease = BeaconRelease("0.1.0", emptyList())

    override suspend fun installBeacon(hostId: String, fetcher: BeaconFetcher): BeaconInstall {
        beaconFailWith?.let { throw it }
        installs++
        beacon = installed("0.1.0", supported = true, linger = beaconLinger == Linger.Enabled)
        return BeaconInstall("0.1.0", beaconLinger, BeaconSource.HOST_DOWNLOAD)
    }

    override suspend fun beaconStatus(hostId: String): BeaconState = beacon

    /** Like the core: an unsupported Beacon is updated, or reported when auto update is off. */
    override suspend fun checkBeacon(hostId: String, fetcher: BeaconFetcher): BeaconCheck {
        val state = beacon
        if (state !is BeaconState.Installed || state.supported) return BeaconCheck(state, BeaconUpdate.NotNeeded)
        if (!autoUpdate) return BeaconCheck(state, BeaconUpdate.Needed)
        val install = installBeacon(hostId, fetcher)
        return BeaconCheck(beacon, BeaconUpdate.Updated(state.version, install))
    }

    override fun beaconAutoUpdate(): Boolean = autoUpdate

    override fun setBeaconAutoUpdate(on: Boolean) {
        autoUpdate = on
    }

    fun installed(version: String, supported: Boolean = true, linger: Boolean = true) =
        BeaconState.Installed(version, 1u, true, "0.1.17", linger, supported, ">=0.1.0, <0.2.0", beaconOs)

    override suspend fun uninstallBeacon(hostId: String) {
        beacon = BeaconState.NotInstalled
    }

    /** Chat snapshots `watchChat` delivers, in order (see [pushChat]). */
    val chat = Channel<ChatSnapshot>(Channel.UNLIMITED)

    /** (host id, terminal) of each `watchChat` call. */
    val chatsOpened = mutableListOf<Pair<String, String>>()
    var chatStopped = false
        private set

    /** Bytes `ChatWatch.image` returns, by image id. */
    val chatImages = mutableMapOf<String, ByteArray>()

    fun pushChat(snapshot: ChatSnapshot) {
        chat.trySend(snapshot)
    }

    override fun watchChat(hostId: String, terminal: String, cursor: String?): ChatWatch {
        chatsOpened += hostId to terminal
        return object : ChatWatch(NoHandle) {
            override suspend fun next(): ChatSnapshot = chat.receive()

            override fun image(id: String): ByteArray? = chatImages[id]

            override fun stop() {
                chatStopped = true
            }

            override fun close() {}
        }
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
    private val saved: MutableList<SavedLaunch> = core?.savedLaunches ?: mutableListOf(),
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

    /** The line is dead without anyone noticing yet (the phone slept): calls fail with `Closed`. */
    var dead = false

    /** Completes on [disconnect]: every watch and view on this connection then ends, as on a cut line. */
    private val gone = kotlinx.coroutines.CompletableDeferred<Unit>()

    override suspend fun terminals(): List<TerminalInfo> {
        calls += "terminals"
        if (dead) throw CoreException.Closed()
        return list
    }

    override suspend fun watchTerminals(): TerminalWatch = FakeWatch(list, updates, gone)
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

    override fun savedLaunches(): List<SavedLaunch> = saved.toList()

    override fun saveLaunch(launch: SavedLaunch): SavedLaunch {
        val stored = launch.copy(id = launch.id.ifEmpty { "L${saved.size + 1}" }, name = launch.name.trim())
        val i = saved.indexOfFirst { it.id == stored.id }
        if (i >= 0) saved[i] = stored else saved += stored
        return stored
    }

    override fun deleteSavedLaunch(id: String) {
        saved.removeAll { it.id == id }
    }

    /** Like herdr-core: a new live Terminal, shown in its labelled Tab (or unplaced). */
    override suspend fun launch(request: LaunchRequest): TerminalInfo {
        calls += "launch"
        launchFailWith?.let { throw it }
        launched += request
        val argv = listOfNotNull(request.preset?.name?.lowercase()) + request.command.split(" ").filter { it.isNotEmpty() }
        val t = TerminalInfo("t${100 + launched.size}", null, "", argv, request.cwd, "live")
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

    /** Terminals attached on this connection, in order. */
    val views = mutableListOf<FakeTerminalView>()

    override suspend fun attachTerminal(address: String, options: AttachOptions): TerminalView =
        FakeTerminalView(address, options).also { views += it }

    override suspend fun disconnect() {
        disconnected = true
        gone.complete(Unit)
        views.forEach { it.end() }
        listener?.onClosed()
    }

    override fun close() {}
}

/** First the connection's list, then whatever the test pushes. */
class FakeWatch(
    private val first: List<TerminalInfo>,
    private val updates: Channel<List<TerminalInfo>>,
    private val gone: kotlinx.coroutines.Deferred<Unit> = kotlinx.coroutines.CompletableDeferred(),
) : TerminalWatch(NoHandle) {
    private var started = false

    override suspend fun next(): List<TerminalInfo> {
        if (!started) {
            started = true
            return first
        }
        if (gone.isCompleted) throw CoreException.Closed()
        return kotlinx.coroutines.selects.select {
            updates.onReceive { it }
            gone.onAwait { throw CoreException.Closed() }
        }
    }

    override fun stop() {}

    override fun close() {}
}

/** An attached Terminal whose screen updates the test pushes. */
class FakeTerminalView(val address: String, val options: AttachOptions) : TerminalView(NoHandle) {
    private val updates = Channel<TerminalUpdate>(Channel.UNLIMITED)
    var detached = false
        private set

    fun push(update: TerminalUpdate) {
        updates.trySend(update)
    }

    /** The stream ends without an exit (the connection dropped). */
    fun end() {
        updates.close()
    }

    override suspend fun next(): TerminalUpdate =
        try {
            updates.receive()
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw CoreException.Closed()
        }

    override suspend fun detach() {
        detached = true
        updates.close()
    }

    /** Every [scrollTo] the UI made, in order. */
    val scrolls = mutableListOf<UInt>()

    override fun scrollTo(lines: UInt): UInt = lines.also { scrolls += it }

    override fun close() {}
}
