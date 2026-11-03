package io.github.shiroel.aries.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.HttpBeaconFetcher
import io.github.shiroel.aries.HttpRelayClient
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.AttachOptions
import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.HttpClient
import io.github.shiroel.aries.core.Incompatibility
import io.github.shiroel.aries.core.JumpHost
import io.github.shiroel.aries.core.KeyInfo
import io.github.shiroel.aries.core.KeyInstall
import io.github.shiroel.aries.core.PublicKeyInfo
import io.github.shiroel.aries.core.SavedLaunch
import io.github.shiroel.aries.core.StructureListener
import io.github.shiroel.aries.core.StructureWatch
import io.github.shiroel.aries.core.QrMatrix
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import io.github.shiroel.aries.core.KeyProtection
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A message for the user: a string resource and its format arguments. */
data class Message(@StringRes val res: Int, val args: List<Any> = emptyList())

sealed interface Screen {
    data object Hosts : Screen
    data class Connecting(val host: Host) : Screen

    /** Editing the jump hosts on the way to [host] and the key for each hop. */
    data class JumpHosts(val host: Host, val jumps: List<JumpHost>, val hostKeyId: String?) : Screen

    /** An open Host: its Spaces, Tabs and Panes. */
    data class Browse(val browser: HostBrowser) : Screen

    /** One Terminal, observed; back returns to [returnTo] (its Host's Browse or the Agents list). */
    data class Terminal(val viewer: TerminalViewer, val returnTo: Screen) : Screen

    /** Every Host's Agents in one list. */
    data class Agents(val board: AgentBoard) : Screen

    /** Every Host's Call history ([HostsModel.calls]). */
    data object Calls : Screen
    /** An Agent's Chat; back returns to [returnTo] (its Host's Browse or the Agents list). */
    data class Chat(val chat: ChatModel, val returnTo: Screen) : Screen
}

sealed interface Prompt {
    /** First connection: ask the user to confirm the Host key, then do [then]. */
    data class TrustHostKey(
        val host: Host,
        val fingerprint: String,
        val then: AfterTrust = AfterTrust.Connect,
    ) : Prompt

    /** Ask for the Host password once, to install the app's public key. */
    data class Password(val host: Host) : Prompt

    /** The public key as a QR code, for scanning on another device. */
    data class KeyQr(val key: PublicKeyInfo, val qr: QrMatrix) : Prompt

    /** First connection through jump host [hop] (0-based): confirm its Host key, then do [then]. */
    data class TrustJumpHostKey(
        val host: Host,
        val hop: UInt,
        val address: String,
        val fingerprint: String,
        val then: AfterTrust = AfterTrust.Connect,
    ) : Prompt

    /** Ask before removing [host] and everything stored for it. */
    data class ConfirmRemove(val host: Host) : Prompt

    /** Something the user must read; [title] and [body] are resources. */
    data class Problem(val title: Message, val body: Message) : Prompt
}

enum class AfterTrust { Connect, InstallKey }

/**
 * UI state over the core API. Compose reads the `by mutableStateOf` fields.
 *
 * [keyWork] runs core calls that may open an SSH key. A biometric-protected
 * key blocks its caller while the prompt is up, so the app passes
 * `Dispatchers.IO`; tests keep the default and stay on the UI thread.
 */
class HostsModel(
    private val core: AriesCoreInterface,
    private val scope: CoroutineScope,
    beaconFetcher: BeaconFetcher = HttpBeaconFetcher(),
    private val keyWork: CoroutineContext = EmptyCoroutineContext,
    pushPlatform: PushPlatform = NoPush,
    private val http: HttpClient = HttpRelayClient(),
) {
    /** The Beacon section of the connected Host. */
    val beacon = BeaconModel(core, scope, beaconFetcher, onRemoved = { onBeaconRemoved() }, keyWork = keyWork)

    /** The push section of the connected Host. */
    val push = PushModel(core, scope, pushPlatform, http)

    private fun onBeaconRemoved() {
        flushPushRevocations()
        beacon.host?.let(push::load)
    }

    /** Sends the Relay revocations that Host or Beacon removal queued. */
    fun flushPushRevocations() {
        scope.launch { core.flushPushRevocations(http) }
    }

    /** Every Host's Calls and the new-Call banner; see [resumeCalls]. */
    val calls = CallsModel(core, scope, keyWork)

    var key by mutableStateOf<PublicKeyInfo?>(null)
        private set
    var keyProtection by mutableStateOf<KeyProtection?>(null)
        private set
    var hosts by mutableStateOf<List<Host>>(emptyList())
        private set
    var screen by mutableStateOf<Screen>(Screen.Hosts)
        private set
    var prompt by mutableStateOf<Prompt?>(null)
        private set
    /** Every SSH key, the default one first. */
    var keys by mutableStateOf<List<KeyInfo>>(emptyList())
        private set
    /** Jump hosts per Host id. */
    var jumps by mutableStateOf<Map<String, List<JumpHost>>>(emptyMap())
        private set

    /** The open Host's connection and structure watch, while [Screen.Browse] shows (the latest line after a reconnect). */
    private var open: Pair<HostConnection, StructureWatch?>? = null

    /** The open Host's line, which reconnects on its own; see [HostLink]. */
    private var browseLink: HostLink? = null

    private fun link(host: Host, conn: HostConnection) = HostLink(
        host, conn, scope,
        connect = { withContext(keyWork) { core.connect(host.id) } },
        onDown = { prompt = it },
    )

    /** Every line the screens use now: the open Host's and the Agent list's. */
    private val links: List<HostLink> get() = listOfNotNull(browseLink, callLink) + agentLinks.values

    /** The phone's network changed (Wi-Fi to LTE, airplane mode): replace every line and restore the screens. */
    fun networkChanged() = links.forEach { it.networkChanged() }

    /** The app is back in the foreground: reconnect any line that died meanwhile. */
    fun foregrounded() = links.forEach { it.check() }

    init {
        guard {
            key = core.publicKey()
            keyProtection = core.keyProtection()
            hosts = core.hosts()
            refreshRoutes()
        }
        flushPushRevocations()
    }

    fun createKey() = guard {
        key = core.ensureKey()
        keyProtection = core.keyProtection()
        keys = core.keys()
    }

    /** Re-seals every SSH key with or without a biometric requirement. */
    fun requireBiometric(required: Boolean) {
        val protection = if (required) KeyProtection.BIOMETRIC else KeyProtection.PLAIN
        scope.launch {
            try {
                withContext(keyWork) { core.setKeyProtection(protection) }
            } catch (e: CoreException) {
                prompt = problemFor(e)
            }
            guard { keyProtection = core.keyProtection() }
        }
    }

    /** Creates another key under the current policy, so it may ask for a biometric unlock. */
    fun createExtraKey(label: String) {
        scope.launch {
            try {
                withContext(keyWork) { core.createKey(label.trim()) }
            } catch (e: CoreException) {
                prompt = problemFor(e)
            }
            guard { keys = core.keys() }
        }
    }

    fun editJumps(host: Host) = guard {
        screen = Screen.JumpHosts(host, core.jumpHosts(host.id), core.hostSshKey(host.id))
    }

    fun saveJumps(host: Host, jumps: List<JumpHost>, hostKeyId: String?) = guard {
        core.setJumpHosts(host.id, jumps)
        core.setHostSshKey(host.id, hostKeyId)
        refreshRoutes()
        screen = Screen.Hosts
    }

    private fun refreshRoutes() {
        keys = core.keys()
        jumps = hosts.associate { it.id to core.jumpHosts(it.id) }
    }

    fun addHost(name: String, address: String, port: Int, user: String) = guard {
        val label = name.ifBlank { address }
        core.addHost(label, Endpoint.Ssh(address.trim(), port.toUShort(), user.trim()))
        hosts = core.hosts()
        refreshRoutes()
        hostsChanged()
    }

    fun connect(host: Host) {
        screen = Screen.Connecting(host)
        scope.launch {
            try {
                val conn = withContext(keyWork) { core.connect(host.id) }
                val browser = HostBrowser(host)
                val link = link(host, conn)
                browser.link = link
                browseLink = link
                browser.savedLaunches = conn.savedLaunches()
                open = conn to null
                screen = Screen.Browse(browser)
                beacon.load(host)
                push.load(host)
                status = watchBrowse(link, browser)
            } catch (e: CoreException) {
                screen = Screen.Hosts
                prompt = when (e) {
                    is CoreException.HostKeyUnknown -> Prompt.TrustHostKey(host, e.fingerprint)
                    is CoreException.JumpHostKeyUnknown -> Prompt.TrustJumpHostKey(host, e.hop, e.address, e.fingerprint)
                    else -> problemFor(e)
                }
            }
        }
    }

    /** Opens the "new Terminal" form on [browser], starting at the Space/Tab shown. */
    fun newTerminal(browser: HostBrowser) {
        val conn = open?.first ?: return
        val structure = browser.structure ?: return
        browser.launchForm =
            LaunchForm(structure, conn.recentCwds(), browser.openSpaceId, browser.tab?.id, browser.savedLaunches)
    }

    /** Saves [form] under its name (new, or replacing the config it edits); the form stays open. */
    fun saveLaunch(browser: HostBrowser, form: LaunchForm) {
        val conn = open?.first ?: return
        form.error = null
        try {
            form.stored(conn.saveLaunch(form.toSaved()))
            browser.savedLaunches = form.saved.toList()
        } catch (e: CoreException) {
            form.error = launchProblem(e)
        }
    }

    fun deleteSavedLaunch(browser: HostBrowser, form: LaunchForm, saved: SavedLaunch) {
        val conn = open?.first ?: return
        form.error = null
        try {
            conn.deleteSavedLaunch(saved.id)
            form.removed(saved.id)
            browser.savedLaunches = form.saved.toList()
        } catch (e: CoreException) {
            form.error = launchProblem(e)
        }
    }

    /** One tap: starts [saved] as stored. If that fails, its form opens with the reason. */
    fun runSaved(browser: HostBrowser, saved: SavedLaunch) {
        val conn = open?.first ?: return
        val structure = browser.structure ?: return
        scope.launch {
            try {
                val terminal = conn.launch(saved.request)
                browser.reveal(saved.request.spaceId, saved.request.tabId, terminal.address)
            } catch (e: CoreException) {
                val form = LaunchForm(structure, conn.recentCwds(), null, null, browser.savedLaunches)
                form.edit(saved)
                form.error = launchProblem(e)
                browser.launchForm = form
            }
        }
    }

    /** Starts [form]'s command on the open Host, then shows the new Terminal; failures stay on the form. */
    fun launch(browser: HostBrowser, form: LaunchForm) {
        val conn = open?.first ?: return
        val request = form.request()
        form.busy = true
        form.error = null
        scope.launch {
            try {
                val terminal = conn.launch(request)
                if (browser.launchForm === form) browser.launchForm = null
                browser.reveal(request.spaceId, request.tabId, terminal.address)
            } catch (e: CoreException) {
                form.error = launchProblem(e)
            } finally {
                form.busy = false
            }
        }
    }

    fun trust(prompt: Prompt.TrustHostKey) {
        this.prompt = null
        guard {
            core.trustHostKey(prompt.host.id, prompt.fingerprint)
            hosts = core.hosts()
        }
        val host = hosts.find { it.id == prompt.host.id } ?: return
        when (prompt.then) {
            AfterTrust.Connect -> connect(host)
            // The password was not kept, so ask for it again.
            AfterTrust.InstallKey -> askPassword(host)
        }
    }

    fun showKeyQr() = guard {
        val k = key ?: return@guard
        core.publicKeyQr()?.let { prompt = Prompt.KeyQr(k, it) }
    }

    fun askPassword(host: Host) {
        prompt = Prompt.Password(host)
    }

    /** Installs the public key on [host] with a one-time password login. The password is not kept. */
    fun installKey(host: Host, password: String) {
        prompt = null
        scope.launch {
            prompt = try {
                val body = when (core.installKeyWithPassword(host.id, password)) {
                    KeyInstall.ADDED -> R.string.key_install_added_body
                    KeyInstall.ALREADY_PRESENT -> R.string.key_install_present_body
                }
                key = core.publicKey()
                keys = core.keys()
                Prompt.Problem(Message(R.string.key_install_done_title), Message(body, listOf(host.name)))
            } catch (e: CoreException) {
                when (e) {
                    is CoreException.HostKeyUnknown -> Prompt.TrustHostKey(host, e.fingerprint, AfterTrust.InstallKey)
                    is CoreException.JumpHostKeyUnknown ->
                        Prompt.TrustJumpHostKey(host, e.hop, e.address, e.fingerprint, AfterTrust.InstallKey)
                    else -> problemFor(e)
                }
            }
        }
    }

    fun trust(prompt: Prompt.TrustJumpHostKey) {
        this.prompt = null
        guard {
            core.trustJumpHostKey(prompt.host.id, prompt.hop, prompt.fingerprint)
            refreshRoutes()
        }
        val host = hosts.find { it.id == prompt.host.id } ?: return
        when (prompt.then) {
            AfterTrust.Connect -> connect(host)
            AfterTrust.InstallKey -> askPassword(host)
        }
    }

    fun askRemove(host: Host) {
        prompt = Prompt.ConfirmRemove(host)
    }

    fun remove(prompt: Prompt.ConfirmRemove) {
        this.prompt = null
        guard {
            core.removeHost(prompt.host.id)
            hosts = core.hosts()
            hostsChanged()
            flushPushRevocations()
        }
    }

    fun dismiss() {
        prompt = null
    }

    /** The open Host's structure and live Agent status, on each line [HostLink] brings; cancelled on [back]. */
    private var status: Job? = null

    private fun watchBrowse(link: HostLink, browser: HostBrowser): Job = scope.launch {
        try {
            link.keep { conn, _ ->
                val structure = conn.watchStructure(object : StructureListener {
                    override fun onStructure(structure: HostStructure) {
                        scope.launch { browser.update(structure) }
                    }

                    override fun onClosed() {
                        scope.launch { link.lost(conn) }
                    }
                })
                open = conn to structure
                try {
                    val watch = conn.watchTerminals()
                    try {
                        // Each change replaces the list, so Agent status stays live.
                        while (true) browser.updateTerminals(watch.next())
                    } finally {
                        watch.stop()
                    }
                } finally {
                    structure.stop()
                }
            }
        } catch (e: CoreException) {
            browser.markClosed()
        }
    }

    /** The open Terminal's stream; cancelled when it closes. */
    private var viewing: Job? = null

    /**
     * Opens [terminal] in observe mode: the desktop keeps its size and the
     * screen follows the Terminal's grid. Entry point for every list that
     * shows Terminals or Agents: from Browse it is a Terminal of the open
     * Host; from [Screen.Agents] it is the Terminal of the Agent whose detail
     * is open. Leaving it ([closeTerminal]) returns to where it was opened.
     */
    fun openTerminal(terminal: TerminalInfo) {
        when (val s = screen) {
            is Screen.Browse -> {
                val link = browseLink ?: return
                observe(link, s.browser.current(terminal), s)
            }
            is Screen.Agents -> {
                val key = s.board.openKey ?: return
                if (key.address != terminal.address) return
                val link = agentLinks[key.hostId] ?: return
                observe(link, s.board.find(key)?.terminal ?: terminal, s)
            }
            else -> return
        }
    }

    /** Re-attaches with `resumed` on each new line, so the screen comes back after a network change. */
    private fun observe(link: HostLink, terminal: TerminalInfo, returnTo: Screen) {
        val viewer = TerminalViewer(terminal)
        viewer.link = link
        screen = Screen.Terminal(viewer, returnTo)
        viewing = scope.launch {
            try {
                link.keep { conn, resumed ->
                    val options = AttachOptions(observe = true, cols = 80u, rows = 24u, history = SCROLLBACK_LINES, resumed = resumed)
                    val view = conn.attachTerminal(terminal.address, options)
                    viewer.scroller = view::scrollTo
                    try {
                        while (true) {
                            when (val u = view.next()) {
                                is TerminalUpdate.Screen -> viewer.apply(u.frame)
                                is TerminalUpdate.Exited -> {
                                    viewer.exited(u.exit)
                                    return@keep
                                }
                            }
                        }
                    } finally {
                        viewer.scroller = null
                        withContext(NonCancellable) { view.detach() }
                        view.close()
                    }
                }
            } catch (e: CoreException) {
                viewer.markClosed()
            }
        }
    }

    /** Leaves the open Terminal (it keeps running) and shows where it was opened from. */
    fun closeTerminal() {
        val s = screen as? Screen.Terminal ?: return
        viewing?.cancel()
        viewing = null
        screen = s.returnTo
    }

    /** Lines and watch jobs of [Screen.Agents], per Host id. */
    private val agentLinks = mutableMapOf<String, HostLink>()
    private val agentConns: Map<String, HostConnection>
        get() = agentLinks.mapNotNull { (id, link) -> link.connection?.let { id to it } }.toMap()
    private val agentJobs = mutableListOf<Job>()

    /** Connects to every Host and shows all their Agents in one list. */
    fun openAgents() {
        val board = AgentBoard(hosts)
        screen = Screen.Agents(board)
        hosts.forEach { host ->
            agentJobs += scope.launch {
                val conn = try {
                    core.connect(host.id)
                } catch (e: CoreException) {
                    board.fail(host.id, problemFor(e).body)
                    return@launch
                }
                val link = link(host, conn)
                agentLinks[host.id] = link
                board.links[host.id] = link
                try {
                    // A new line starts from its snapshot, so what changed while away shows.
                    link.keep { c, _ ->
                        val watch = c.watchTerminals()
                        try {
                            while (true) board.update(host.id, watch.next())
                        } finally {
                            watch.stop()
                        }
                    }
                } catch (e: CoreException) {
                    board.markClosed(host.id)
                }
            }
        }
    }

    /** Opens an Agent's detail; a Done Agent counts as seen there, for every client. */
    fun openAgent(board: AgentBoard, entry: AgentEntry) {
        board.open(entry.key)
        val t = entry.terminal
        if (t.agentStatus != AgentStatus.Done) return
        labelWrite(board, entry) { it.markSeen(t.address, t.turn) }
    }

    /** Pins or unpins an Agent for every client. */
    fun pin(board: AgentBoard, entry: AgentEntry, pinned: Boolean) =
        labelWrite(board, entry) { it.setPinned(entry.terminal.address, pinned) }

    private fun labelWrite(board: AgentBoard, entry: AgentEntry, write: suspend (HostConnection) -> TerminalInfo) {
        val conn = agentConns[entry.host.id] ?: return
        scope.launch {
            try {
                board.replace(entry.host.id, write(conn))
            } catch (e: CoreException) {
                prompt = problemFor(e)
            }
        }
    }

    private fun closeAgents() {
        agentJobs.forEach { it.cancel() }
        agentJobs.clear()
        agentLinks.values.forEach { it.close() }
        agentLinks.clear()
    }

    /** The app is in front: follow Calls (banner) until [pauseCalls]. */
    private var callsActive = false

    fun resumeCalls() {
        callsActive = true
        calls.watch(hosts, everyHost = screen == Screen.Calls)
    }

    fun pauseCalls() {
        callsActive = false
        calls.stopAll()
    }

    private fun hostsChanged() {
        if (callsActive || screen == Screen.Calls) calls.watch(hosts, everyHost = screen == Screen.Calls)
    }

    /** Opens Call history for every Host (Hosts whose key needs a biometric unlock too). */
    fun openCalls() {
        back()
        calls.dismissBanner()
        screen = Screen.Calls
        calls.watch(hosts, everyHost = true)
    }

    /** The line a Call's Terminal is shown over, until Call history is left. */
    private var callLink: HostLink? = null

    /** Shows the Terminal that sent [entry]'s Call; back returns to Call history. */
    fun openCallTerminal(entry: CallEntry) {
        val address = entry.call.terminal ?: return
        if (screen != Screen.Calls) return
        scope.launch {
            try {
                val link = callLink?.takeIf { it.host.id == entry.host.id }
                    ?: link(entry.host, withContext(keyWork) { core.connect(entry.host.id) }).also { fresh ->
                        callLink?.close()
                        callLink = fresh
                    }
                val terminal = link.keep { conn, _ -> conn.terminals() }
                    .find { it.address == address && it.state == "live" }
                if (terminal == null) {
                    prompt = Prompt.Problem(
                        Message(R.string.call_terminal_gone_title),
                        Message(R.string.call_terminal_gone_body, listOf(address, entry.host.name)),
                    )
                    return@launch
                }
                if (screen == Screen.Calls) observe(link, terminal, Screen.Calls)
            } catch (e: CoreException) {
                prompt = when (e) {
                    is CoreException.HostKeyUnknown -> Prompt.TrustHostKey(entry.host, e.fingerprint)
                    else -> problemFor(e)
                }
            }
        }
    }

    /** Opens the Chat of the Agent in [terminal] on [host], from Browse or the Agents list. */
    fun openChat(host: Host, terminal: TerminalInfo) {
        val from = screen
        if (from !is Screen.Browse && from !is Screen.Agents) return
        val chat = ChatModel(core, host, terminal)
        screen = Screen.Chat(chat, from)
        chat.start(scope)
    }

    /** Leaves the Chat (the Agent keeps running) for where it was opened. */
    fun closeChat() {
        val s = screen as? Screen.Chat ?: return
        s.chat.stop()
        screen = s.returnTo
    }

    fun back() {
        (screen as? Screen.Chat)?.chat?.stop()
        viewing?.cancel()
        viewing = null
        callLink?.close()
        callLink = null
        if (screen == Screen.Calls && callsActive) {
            // Leaving Call history: keep only the watches a banner may use.
            calls.stopAll()
            calls.watch(hosts, everyHost = false)
        }
        if (screen is Screen.Agents) closeAgents()
        screen = Screen.Hosts
        status?.cancel()
        status = null
        browseLink?.close()
        browseLink = null
        val (_, watch) = open ?: return
        open = null
        watch?.stop()
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: CoreException) {
            prompt = problemFor(e)
        }
    }
}

fun problemFor(e: CoreException): Prompt.Problem {
    val failed = Message(R.string.connect_failed_title)
    return when (e) {
        is CoreException.HostKeyChanged -> Prompt.Problem(
            Message(R.string.host_key_changed_title),
            Message(R.string.host_key_changed_body, listOf(e.expected, e.actual)),
        )
        is CoreException.Incompatible -> when (val r = e.reason) {
            is Incompatibility.AppTooOld -> Prompt.Problem(
                Message(R.string.incompatible_title),
                Message(R.string.incompatible_app_too_old, listOf(r.hostGeneration.toInt())),
            )
            is Incompatibility.CoreTooOld -> Prompt.Problem(
                Message(R.string.incompatible_title),
                Message(R.string.incompatible_core_too_old, listOf(r.version, r.minimum)),
            )
            is Incompatibility.MissingMethods -> Prompt.Problem(
                Message(R.string.incompatible_title),
                Message(R.string.incompatible_missing_methods, listOf(r.methods.joinToString(", "))),
            )
        }
        is CoreException.JumpHostKeyChanged -> Prompt.Problem(
            Message(R.string.jump_key_changed_title),
            Message(R.string.jump_key_changed_body, listOf(e.hop.toInt() + 1, e.address, e.expected, e.actual)),
        )
        is CoreException.JumpAuthRejected ->
            Prompt.Problem(failed, Message(R.string.connect_jump_auth_rejected, listOf(e.hop.toInt() + 1, e.address)))
        is CoreException.PasswordRejected -> Prompt.Problem(
            Message(R.string.key_install_failed_title),
            Message(R.string.key_install_password_rejected),
        )
        is CoreException.KeyInstallFailed -> Prompt.Problem(
            Message(R.string.key_install_failed_title),
            Message(R.string.key_install_write_failed, listOf(e.detail)),
        )
        is CoreException.AuthRejected -> Prompt.Problem(failed, Message(R.string.connect_auth_rejected))
        is CoreException.NoKey -> Prompt.Problem(failed, Message(R.string.connect_no_key))
        is CoreException.KeyLocked -> Prompt.Problem(
            Message(R.string.key_locked_title),
            Message(R.string.key_locked_body),
        )
        is CoreException.BiometricUnavailable -> Prompt.Problem(
            Message(R.string.key_biometric_unavailable_title),
            Message(R.string.key_biometric_unavailable_body),
        )
        is CoreException.Unreachable -> Prompt.Problem(failed, Message(R.string.connect_unreachable, listOf(e.detail)))
        is CoreException.Protocol -> Prompt.Problem(failed, Message(R.string.connect_protocol, listOf(e.detail)))
        else -> Prompt.Problem(failed, Message(R.string.connect_other, listOf(e.message ?: "")))
    }
}

/** Scrollback asked for when attaching a Terminal (what the desktop app keeps is 2000). */
const val SCROLLBACK_LINES = 2000u
