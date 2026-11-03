package io.github.shiroel.aries.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.HttpBeaconFetcher
import io.github.shiroel.aries.HttpRelayClient
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.AttachMode
import io.github.shiroel.aries.core.AttachOptions
import io.github.shiroel.aries.core.TerminalView
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withContext

/** A message for the user: a string resource and its format arguments. */
data class Message(@StringRes val res: Int, val args: List<Any> = emptyList())

sealed interface Screen {
    data object Hosts : Screen
    data object Settings : Screen
    data class Connecting(val host: Host) : Screen

    /** Editing the jump hosts on the way to [host] and the key for each hop. */
    data class JumpHosts(val host: Host, val jumps: List<JumpHost>, val hostKeyId: String?) : Screen

    /** An open Host: its Spaces, Tabs and Panes. */
    data class Browse(val browser: HostBrowser) : Screen

    /** One Terminal, observed; back returns to [returnTo] (its Host's Browse or the Agents list). */
    data class Terminal(val viewer: TerminalViewer, val returnTo: Screen) : Screen

    /** Every Host's Agents in one list. */
    data class Agents(val board: AgentBoard) : Screen

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
    private val beaconFetcher: BeaconFetcher = HttpBeaconFetcher(),
    private val keyWork: CoroutineContext = EmptyCoroutineContext,
    private val pushPlatform: PushPlatform = NoPush,
    private val http: HttpClient = HttpRelayClient(),
) {
    /** Current Host's settings models; a previous Host's pending work keeps its own state. */
    var beacon by mutableStateOf(BeaconModel(core, scope, beaconFetcher, keyWork = keyWork))
        private set
    var push by mutableStateOf(PushModel(core, scope, pushPlatform, http, keyWork))
        private set

    private fun loadHostSettings(host: Host) {
        val hostPush = PushModel(core, scope, pushPlatform, http, keyWork)
        push = hostPush
        beacon = BeaconModel(core, scope, beaconFetcher, onRemoved = {
            flushPushRevocations()
            hostPush.load(host)
        }, keyWork = keyWork)
        beacon.load(host)
        hostPush.load(host)
    }

    /** Sends the Relay revocations that Host or Beacon removal queued. */
    fun flushPushRevocations() {
        scope.launch { core.flushPushRevocations(http) }
    }

    /** Every Host's Calls for the inbox; see [resumeCalls]. */
    val calls = CallsModel(core, scope, keyWork)
    /** Chat cache retention and per-Host clearing (ticket 44). */
    val chatCache = ChatCacheModel(core, scope, keyWork)

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

    /** How opening a Terminal attaches (the user's option; observe by default). */
    var attachMode by mutableStateOf(AttachMode.OBSERVE)
        private set

    /** How an Agent opens: the view last picked with the Terminal/Chat toggle (Terminal by default). */
    var agentView by mutableStateOf(AgentView.TERMINAL)
        private set

    /** Settings subpages share the drawer destination and own all configuration. */
    var settingsHost by mutableStateOf<Host?>(null)
        private set
    var settingsKeys by mutableStateOf(false)
        private set
    var missingKeyHostId by mutableStateOf<String?>(null)
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

    /**
     * The phone's network changed (Wi-Fi to LTE, airplane mode): replace every
     * line and restore the screens. Each line reconnects once, on its own path:
     * Host connections through their [HostLink], the open Chat's stream (its
     * own line, see `ChatWatch`) through [ChatModel.reconnect].
     */
    fun networkChanged() {
        links.forEach { it.networkChanged() }
        liveChat?.reconnect()
    }

    /** The app is back in the foreground: reconnect any line that died meanwhile. */
    fun foregrounded() {
        links.forEach { it.check() }
        liveChat?.retryNow()
        // Live connections publish their lists in the core. Other Hosts
        // get a short pull while the foreground biometric prompt is available.
        hosts.filter { host -> links.none { it.host.id == host.id && it.connection != null } }
            .forEach { refreshLastKnownState(it.id) }
    }

    /** Foreground refresh, including biometric Hosts; surfaces use WorkManager instead. */
    fun refreshLastKnownState(hostId: String) {
        scope.launch {
            try {
                val connection = links.firstOrNull { it.host.id == hostId }?.connection
                withContext(keyWork) {
                    if (connection != null) connection.terminals() else core.snapshot(hostId)
                }
            } catch (_: CoreException) {
                // Keep the previous value and its age on a failed pull.
            }
        }
    }

    /** The Chat that is open, or still running under the Terminal opened from it. */
    private val liveChat: ChatModel?
        get() = (screen as? Screen.Chat ?: (screen as? Screen.Terminal)?.returnTo as? Screen.Chat)?.chat

    init {
        guard {
            key = core.publicKey()
            keyProtection = core.keyProtection()
            hosts = core.hosts()
            refreshRoutes()
            attachMode = core.attachMode()
            agentView = core.agentView()
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
        screen = Screen.Settings
    }

    private fun refreshRoutes() {
        keys = core.keys()
        jumps = hosts.associate { it.id to core.jumpHosts(it.id) }
    }

    fun addHost(name: String, address: String, port: Int, user: String) = guard {
        val label = name.trim().ifBlank { address.trim() }
        core.addHost(label, Endpoint.Ssh(address.trim(), port.toUShort(), user.trim()))
        hosts = core.hosts()
        refreshRoutes()
        hostsChanged()
    }

    /** Saves in place; the core closes old lines only when the endpoint changed. */
    suspend fun saveHost(host: Host, name: String, address: String, port: Int, user: String): Boolean {
        return try {
            val previous = hosts.firstOrNull { it.id == host.id } ?: host
            val endpoint = Endpoint.Ssh(address.trim(), port.toUShort(), user.trim())
            withContext(keyWork) {
                core.editHost(host.id, name.trim().ifBlank { address.trim() }, endpoint)
            }
            hosts = core.hosts()
            refreshRoutes()
            if (previous.endpoint != endpoint) calls.stopAll()
            hostsChanged()
            true
        } catch (e: CoreException) {
            prompt = problemFor(e)
            false
        }
    }

    fun connect(host: Host) {
        missingKeyHostId = null
        val connecting = Screen.Connecting(host)
        screen = connecting
        scope.launch {
            try {
                val conn = withContext(keyWork) { core.connect(host.id) }
                if (screen !== connecting) {
                    conn.disconnect()
                    return@launch
                }
                val browser = HostBrowser(host)
                val link = link(host, conn)
                browser.link = link
                browseLink = link
                browser.savedLaunches = conn.savedLaunches()
                open = conn to null
                screen = Screen.Browse(browser)
                loadHostSettings(host)
                status = watchBrowse(link, browser)
            } catch (e: CoreException) {
                if (screen !== connecting) return@launch
                screen = Screen.Hosts
                if (e is CoreException.NoKey && key == null) {
                    missingKeyHostId = host.id
                    return@launch
                }
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
                val body = when (withContext(keyWork) { core.installKeyWithPassword(host.id, password) }) {
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
            settingsHost = null
            settingsKeys = false
            screen = Screen.Settings
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
                        while (true) {
                            val list = watch.next()
                            browser.updateTerminals(list)
                            chatOn(browser.host.id)?.update(list)
                            terminalOn(browser.host.id)?.update(list)
                        }
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
     * Opens [terminal]: an Agent in the saved [agentView], any other
     * Terminal as a Terminal in observe mode (the desktop keeps its size and
     * the screen follows the Terminal's grid). From Browse it is a Terminal
     * of the open Host. Agents lists use [openAgent] to retain the whole Host/address
     * identity. Leaving a Terminal returns to where it was opened.
     */
    fun openTerminal(terminal: TerminalInfo) {
        when (val s = screen) {
            is Screen.Browse -> {
                val link = browseLink ?: return
                show(link, s.browser.current(terminal), s)
            }
            // From a Chat (a Blocked Agent): back returns to the still-live Chat.
            is Screen.Chat -> {
                val link = s.chat.link ?: return
                if (terminal.address != s.chat.terminal.address) return
                observe(link, s.chat.terminal, s)
            }
            else -> return
        }
    }

    /** Shows [terminal] over [returnTo]: an Agent in the saved [agentView], any other Terminal as a Terminal. */
    private fun show(link: HostLink, terminal: TerminalInfo, returnTo: Screen) {
        if (terminal.agentStatus != null && agentView == AgentView.CHAT) {
            chat(link, terminal, returnTo)
        } else {
            observe(link, terminal, returnTo)
        }
    }

    private fun chat(link: HostLink?, terminal: TerminalInfo, returnTo: Screen) {
        val host = link?.host ?: return
        val chat = ChatModel(core, host, terminal, link, keyWork)
        screen = Screen.Chat(chat, returnTo)
        chat.start(scope)
    }

    /**
     * The Terminal/Chat toggle: shows the open Agent as [view] in place, so
     * back still returns to where it was opened, and saves [view] for the
     * next Agent opened.
     */
    fun switchView(view: AgentView) {
        when (val s = screen) {
            is Screen.Terminal -> {
                if (view != AgentView.CHAT || s.viewer.terminal.agentStatus == null) return
                if (s.returnTo is Screen.Chat) {
                    // Opened from that Chat's Blocked notice: the Chat is still live under it.
                    closeTerminal()
                } else {
                    val link = s.viewer.link ?: return
                    viewing?.cancel()
                    viewing = null
                    chat(link, s.viewer.terminal, s.returnTo)
                }
            }
            is Screen.Chat -> {
                if (view != AgentView.TERMINAL) return
                val link = s.chat.link ?: return
                s.chat.stop()
                observe(link, s.chat.terminal, s.returnTo)
            }
            else -> return
        }
        guard {
            core.setAgentView(view)
            agentView = core.agentView()
        }
    }

    /** Re-attaches with `resumed` on each new line, so the screen comes back after a network change. */
    private fun observe(link: HostLink, terminal: TerminalInfo, returnTo: Screen) {
        val viewer = TerminalViewer(terminal)
        viewer.link = link
        if (attachMode == AttachMode.INPUT) viewer.startTyping()
        screen = Screen.Terminal(viewer, returnTo)
        viewing = scope.launch {
            try {
                link.keep { conn, resumed ->
                    if (!viewer.typing) {
                        // Watch until the input is focused, then let go of the observer.
                        val options = AttachOptions(observe = true, cols = 80u, rows = 24u, history = SCROLLBACK_LINES, resumed = resumed)
                        val exited = coroutineScope {
                            val watching = async { draw(conn, viewer, options, null) }
                            val focused = async { snapshotFlow { viewer.typing }.first { it } }
                            val done = select { watching.onAwait { it }; focused.onAwait { false } }
                            focused.cancel()
                            watching.cancelAndJoin()
                            done
                        }
                        if (exited) return@keep
                    }
                    // An ordinary viewer at the phone's grid; from now on the phone sets the size.
                    val (cols, rows) = snapshotFlow { viewer.phoneGrid }.filterNotNull().first()
                    val options = AttachOptions(
                        observe = false, cols = cols.toUShort(), rows = rows.toUShort(), history = SCROLLBACK_LINES, resumed = resumed,
                    )
                    draw(conn, viewer, options) { view ->
                        // A failed send means the line is going; next() reports it and the link re-attaches.
                        launch {
                            try {
                                snapshotFlow { viewer.phoneGrid }.filterNotNull().distinctUntilChanged()
                                    .dropWhile { it == cols to rows }
                                    .collect { (c, r) -> view.resize(c.toUShort(), r.toUShort()) }
                            } catch (e: CoreException) {
                            }
                        }
                        launch {
                            try {
                                // One at a time, so keys arrive in the order they were typed. Keys typed
                                // while the line is down wait here for the next attach.
                                for (input in viewer.inputs) {
                                    when (input) {
                                        is TerminalInput.Text -> view.sendText(input.text)
                                        is TerminalInput.Paste -> view.pasteText(input.text)
                                        is TerminalInput.Key -> view.sendKey(input.key, input.mods)
                                    }
                                }
                            } catch (e: CoreException) {
                            }
                        }
                    }
                }
            } catch (e: CoreException) {
                viewer.markClosed()
            }
        }
    }

    /**
     * Attaches [viewer] with [options] and draws its updates; [alongside]
     * runs next to it (typing, resizing) and stops with it. Returns true once
     * the program exited; a dropped line throws [CoreException.Closed].
     * Detaches on the way out, also when cancelled.
     */
    private suspend fun draw(
        conn: HostConnection,
        viewer: TerminalViewer,
        options: AttachOptions,
        alongside: (CoroutineScope.(TerminalView) -> Unit)?,
    ): Boolean {
        val view = conn.attachTerminal(viewer.terminal.address, options)
        viewer.scroller = view::scrollTo
        try {
            return coroutineScope {
                val side = alongside?.let { f -> launch { f(view) } }
                try {
                    var exited = false
                    while (!exited) {
                        when (val u = view.next()) {
                            is TerminalUpdate.Screen -> viewer.apply(u.frame)
                            is TerminalUpdate.Exited -> {
                                viewer.exited(u.exit)
                                exited = true
                            }
                        }
                    }
                    true
                } finally {
                    side?.cancel()
                }
            }
        } finally {
            viewer.scroller = null
            withContext(NonCancellable) { view.detach() }
            view.close()
        }
    }

    fun setDefaultAttachMode(mode: AttachMode) = guard {
        core.setAttachMode(mode)
        attachMode = core.attachMode()
    }

    /** Leaves the open Terminal (it keeps running) and shows where it was opened from. */
    fun closeTerminal() {
        val s = screen as? Screen.Terminal ?: return
        viewing?.cancel()
        viewing = null
        if (s.viewer.link === callLink && s.returnTo !is Screen.Chat) {
            callLink?.close()
            callLink = null
        }
        screen = s.returnTo
    }

    /** Lines and watch jobs of [Screen.Agents], per Host id. */
    private val agentLinks = mutableMapOf<String, HostLink>()
    private val agentConns: Map<String, HostConnection>
        get() = agentLinks.mapNotNull { (id, link) -> link.connection?.let { id to it } }.toMap()
    private val agentJobs = mutableListOf<Job>()

    /** Connects to every Host and shows all their Agents in one list. */
    fun openAgents() {
        back()
        openAgents(focus = null)
    }

    /**
     * A tapped Agent notification (warm or cold start): leaves whatever is
     * open, shows the Agents list and, once [hostId]'s list arrives, opens
     * that Agent in its saved view (back returns to the list). A Terminal that is
     * no longer live, or a Host that was removed, is explained instead.
     */
    fun openPushedAgent(hostId: String, address: String) {
        back()
        val host = hosts.find { it.id == hostId }
        openAgents(focus = host?.let { AgentKey(it.id, address) })
        if (host == null) {
            prompt = Prompt.Problem(Message(R.string.push_terminal_gone_title), Message(R.string.push_host_gone_body, listOf(address)))
        }
    }

    /** [focus]: the Agent whose Terminal to open once its Host's list arrives. */
    private fun openAgents(focus: AgentKey?) {
        val board = AgentBoard(hosts)
        val agents = Screen.Agents(board)
        screen = agents
        hosts.forEach { host ->
            var focusing = focus?.takeIf { it.hostId == host.id }
            agentJobs += scope.launch {
                val conn = try {
                    withContext(keyWork) { core.connect(host.id) }
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
                        val structure = c.watchStructure(object : StructureListener {
                            override fun onStructure(structure: HostStructure) {
                                scope.launch { board.updateStructure(host.id, structure) }
                            }

                            override fun onClosed() { scope.launch { link.lost(c) } }
                        })
                        try {
                            val watch = c.watchTerminals()
                            try {
                                while (true) {
                                    val list = watch.next()
                                    board.update(host.id, list)
                                    chatOn(host.id)?.update(list)
                                    terminalOn(host.id)?.update(list)
                                    focusing?.let { key ->
                                        focusing = null
                                        if (screen === agents) focusOn(agents, host, key, list)
                                    }
                                }
                            } finally {
                                watch.stop()
                            }
                        } finally {
                            structure.stop()
                        }
                    }
                } catch (e: CoreException) {
                    board.markClosed(host.id)
                }
            }
        }
    }

    private fun focusOn(agents: Screen.Agents, host: Host, key: AgentKey, list: List<TerminalInfo>) {
        val terminal = list.find { it.address == key.address && it.state == "live" }
        if (terminal == null) {
            prompt = Prompt.Problem(
                Message(R.string.push_terminal_gone_title),
                Message(R.string.push_terminal_gone_body, listOf(key.address, host.name)),
            )
            return
        }
        openAgent(agents.board, AgentEntry(host, terminal))
    }

    /** Opens an Agent in its saved view over the list; the visible screen owns seen sync. */
    fun openAgent(board: AgentBoard, entry: AgentEntry) {
        val agents = screen as? Screen.Agents ?: return
        if (agents.board !== board) return
        val current = board.find(entry.key) ?: return
        val link = agentLinks[current.host.id] ?: return
        val t = current.terminal
        show(link, t, agents)
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

    /** The app is in front: follow eligible Calls until [pauseCalls]. */
    private var callsActive = false

    var callInboxOpen by mutableStateOf(false)
        private set

    fun openCallInbox() {
        callInboxOpen = true
        calls.dismissBanner()
        calls.watch(hosts, everyHost = true)
    }

    fun closeCallInbox() {
        if (!callInboxOpen) return
        callInboxOpen = false
        callOpenRequest++
        calls.stopAll()
        if (callsActive) calls.watch(hosts, everyHost = false)
    }

    fun resumeCalls() {
        callsActive = true
        calls.watch(hosts, everyHost = callInboxOpen)
    }

    fun pauseCalls() {
        callsActive = false
        calls.stopAll()
    }

    private fun hostsChanged() {
        if (callsActive || callInboxOpen) calls.watch(hosts, everyHost = callInboxOpen)
    }

    /** Available live Agent data, including the screen beneath an open Terminal/Chat. */
    val drawerAgents: List<AgentEntry>
        get() {
            fun entries(screen: Screen): List<AgentEntry> = when (screen) {
                is Screen.Agents -> screen.board.agents
                is Screen.Browse -> screen.browser.agents
                is Screen.Terminal -> entries(screen.returnTo)
                is Screen.Chat -> entries(screen.returnTo)
                else -> emptyList()
            }
            return entries(screen)
        }

    /** Resolve a Call against snapshots already available; opening the inbox starts no Agent watch. */
    fun callCallerName(entry: CallEntry): String? {
        val address = entry.call.terminal ?: return null
        val key = AgentKey(entry.host.id, address)
        fun find(route: Screen): TerminalInfo? = when (route) {
            is Screen.Agents -> route.board.find(key)?.terminal
            is Screen.Browse -> if (route.browser.host.id == key.hostId) route.browser.findTerminal(address) else null
            is Screen.Terminal -> if (route.viewer.link?.host?.id == key.hostId && route.viewer.terminal.address == address)
                route.viewer.terminal else find(route.returnTo)
            is Screen.Chat -> if (route.chat.host.id == key.hostId && route.chat.terminal.address == address)
                route.chat.terminal else find(route.returnTo)
            else -> null
        }
        return find(screen)?.let(::displayName)?.takeIf { it.isNotBlank() }
    }

    fun openHost(host: Host) {
        back()
        connect(host)
    }

    /** Uses whole Host/address identity and the existing saved-view/notification route. */
    fun openDrawerAgent(entry: AgentEntry) {
        val board = (screen as? Screen.Agents)?.board
        if (board != null) {
            openAgent(board, entry)
        } else {
            openPushedAgent(entry.host.id, entry.terminal.address)
        }
    }

    /** Settings is a drawer destination. */
    fun openSettings() {
        back()
        settingsHost = null
        settingsKeys = false
        screen = Screen.Settings
    }

    /** Shared destination for Host menus, Browse guidance and inbox install links (ticket 51). */
    fun openHostSettings(host: Host) {
        openSettings()
        settingsHost = host
        loadHostSettings(host)
    }

    fun openKeySettings() {
        openSettings()
        settingsKeys = true
    }

    fun settingsBack() {
        if (screen is Screen.JumpHosts) {
            screen = Screen.Settings
        } else if (settingsHost != null || settingsKeys) {
            settingsHost = null
            settingsKeys = false
        } else {
            back()
        }
    }

    fun setDefaultAgentView(view: AgentView) = guard {
        core.setAgentView(view)
        agentView = core.agentView()
    }

    /** The connection owned by a Chat opened from the inbox. */
    private var callLink: HostLink? = null
    private var callOpenRequest = 0

    /** Opens the caller in Chat without changing the saved Agent view. */
    fun openCallChat(entry: CallEntry) {
        val address = entry.call.terminal ?: return
        if (!callInboxOpen) return
        val origin = screen
        if (origin is Screen.Chat && origin.chat.host.id == entry.host.id && origin.chat.terminal.address == address) {
            closeCallInbox()
            return
        }
        val request = ++callOpenRequest
        fun current() = callInboxOpen && screen === origin && request == callOpenRequest
        scope.launch {
            var pending: HostLink? = null
            try {
                val link = link(entry.host, withContext(keyWork) { core.connect(entry.host.id) })
                pending = link
                if (!current()) return@launch
                val terminal = link.keep { conn, _ -> conn.terminals() }
                    .find { it.address == address && it.state == "live" }
                if (!current()) return@launch
                if (terminal == null) {
                    prompt = Prompt.Problem(
                        Message(R.string.call_terminal_gone_title),
                        Message(R.string.call_terminal_gone_body, listOf(address, entry.host.name)),
                    )
                    return@launch
                }
                // A new caller replaces the current Agent, retaining its Browse/list destination.
                while (screen is Screen.Terminal || screen is Screen.Chat) {
                    if (screen is Screen.Terminal) closeTerminal() else closeChat()
                }
                val returnTo = screen
                callLink?.close()
                callLink = link
                pending = null
                closeCallInbox()
                chat(link, terminal, returnTo)
            } catch (e: CoreException) {
                if (current()) prompt = when (e) {
                    is CoreException.HostKeyUnknown -> Prompt.TrustHostKey(entry.host, e.fingerprint)
                    else -> problemFor(e)
                }
            } finally {
                pending?.close()
            }
        }
    }

    /** Leaves the Chat (the Agent keeps running) for where it was opened. */
    fun closeChat() {
        val s = screen as? Screen.Chat ?: return
        s.chat.stop()
        if (s.chat.link === callLink) {
            callLink?.close()
            callLink = null
        }
        screen = s.returnTo
    }

    /** The Chat that is open (or under the Terminal opened from it) on [hostId]. */
    private fun chatOn(hostId: String): ChatModel? = liveChat?.takeIf { it.host.id == hostId }

    private fun terminalOn(hostId: String): TerminalViewer? =
        (screen as? Screen.Terminal)?.viewer?.takeIf { it.link?.host?.id == hostId }

    fun back() {
        (screen as? Screen.Chat)?.chat?.stop()
        ((screen as? Screen.Terminal)?.returnTo as? Screen.Chat)?.chat?.stop()
        viewing?.cancel()
        viewing = null
        callLink?.close()
        callLink = null
        closeCallInbox()
        // Also under a Terminal or Chat opened from the Agents list.
        closeAgents()
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
        is CoreException.InvalidHost -> Prompt.Problem(
            Message(R.string.host_edit_title), Message(R.string.host_edit_invalid_body),
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
