package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.HerdrCoreState
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.HttpBeaconFetcher
import io.github.shiroel.aries.HttpRelayClient
import io.github.shiroel.aries.PushTarget
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentPreset
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.ChatEntryBody
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Bounded reuse for a short trip to another app; never a resident background connection. */
const val SCREEN_CONNECTION_GRACE_MS = 120_000L

/** Local VT operations are owned by draw, so close waits for an in-flight search. */
private data class TerminalLocalOperation(
    val apply: (TerminalView) -> Unit,
    val result: CompletableDeferred<Unit> = CompletableDeferred(),
)

/** A message for the user: a string resource and its format arguments. */
data class Message(@StringRes val res: Int, val args: List<Any> = emptyList())

sealed interface Screen {
    data object Hosts : Screen
    data object Settings : Screen
    data class Connecting(val host: Host, val link: HostLink, val cachedBrowser: HostBrowser? = null) : Screen

    /** Editing the jump hosts on the way to [host] and the key for each hop. */
    data class JumpHosts(val host: Host, val jumps: List<JumpHost>, val hostKeyId: String?) : Screen

    /** An open Host: its Spaces, Tabs and Panes. */
    data class Browse(val browser: HostBrowser) : Screen

    /** One Terminal. Drafts belong to HostsModel; Back returns to [returnTo]. */
    data class Terminal(
        val viewer: TerminalViewer,
        val returnTo: Screen,
        /** An in-place toggle belongs to the same Chat visit. */
        val fromChatToggle: Boolean = false,
    ) : Screen

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

    /** An untrusted entry may connect, but only this approval can execute its reviewed request. */
    data class ConfirmSavedLaunch(val browser: HostBrowser, val saved: SavedLaunch) : Prompt

    /** Something the user must read; [title] and [body] are resources. */
    data class Problem(val title: Message, val body: Message, val recoverKey: Boolean = false) : Prompt
}

enum class AfterTrust { Connect, InstallKey }

enum class HostAddMethod { QR, MANUAL }

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
    private val onSavedLaunchesChanged: () -> Unit = {},
    private val locationStore: LastLocationStore? = null,
    private val onCallAnswered: (Host, String, CallAnswerState) -> Unit = { _, _, _ -> },
    private val elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    internal val pairingReview = QrPairingReview()
    /** Current Host's settings models; a previous Host's pending work keeps its own state. */
    var beacon by mutableStateOf(BeaconModel(core, scope, beaconFetcher, keyWork = keyWork))
        private set
    var herdrInstall by mutableStateOf(HerdrInstallModel(core, scope, beaconFetcher, keyWork))
        private set
    var push by mutableStateOf(PushModel(core, scope, pushPlatform, http, keyWork))
        private set

    private data class HostSettings(
        val connection: Triple<Endpoint, List<JumpHost>, String?>,
        val beacon: BeaconModel,
        val herdr: HerdrInstallModel,
        val push: PushModel,
    )
    private val hostSettings = mutableMapOf<String, HostSettings>()

    private fun loadHostSettings(requestedHost: Host) {
        val host = hosts.firstOrNull { it.id == requestedHost.id } ?: return
        val connection = Triple(host.endpoint, core.jumpHosts(host.id), core.hostSshKey(host.id))
        hostSettings[host.id]?.takeIf { it.connection != connection }?.let { old ->
            old.beacon.invalidate()
            old.herdr.invalidate()
            hostSettings.remove(host.id)
        }
        val settings = hostSettings.getOrPut(host.id) {
            val hostPush = PushModel(core, scope, pushPlatform, http, keyWork)
            HostSettings(
                connection,
                BeaconModel(core, scope, beaconFetcher, onRemoved = {
                    flushPushRevocations()
                    hostPush.load(host)
                }, keyWork = keyWork),
                HerdrInstallModel(core, scope, beaconFetcher, keyWork),
                hostPush,
            )
        }
        beacon = settings.beacon
        herdrInstall = settings.herdr
        push = settings.push
        // Each model rejects refresh/re-entry while its operation owns the Host.
        herdrInstall.load(host)
        beacon.load(host)
        push.load(host)
    }

    /** Sends the Relay revocations that Host or Beacon removal queued. */
    fun flushPushRevocations() {
        scope.launch { core.flushPushRevocations(http) }
    }

    /** Every Host's Calls for the inbox; see [resumeCalls]. */
    val calls = CallsModel(core, scope, keyWork, onCallAnswered)
    /** Chat cache retention and per-Host clearing (ticket 44). */
    val chatCache = ChatCacheModel(core, scope, keyWork)
    val diagnosticHistory = DiagnosticsModel(core, scope, keyWork)

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

    // Settings overlays the route owner, so appearance changes never retire a connection or draft.
    private var settingsReturnTo by mutableStateOf<Screen?>(null)
    private var pendingSettingsState: SettingsNavigationState? = null
    private var settingsColdReturn by mutableStateOf<SettingsReturnState?>(null)
    private val ownedScreen: Screen get() = settingsReturnTo ?: screen

    /** Settings subpages share the drawer destination and own all configuration. */
    var settingsHost by mutableStateOf<Host?>(null)
        private set
    var settingsDiagnostics by mutableStateOf(false)
        private set
    var settingsKeys by mutableStateOf(false)
        private set
    var missingKeyHostId by mutableStateOf<String?>(null)
        private set

    /** Shared entry for the welcome guide and empty states; key setup stays in this flow. */
    var hostAddMethod by mutableStateOf<HostAddMethod?>(null)
        private set
    var addedHost by mutableStateOf<Host?>(null)
        private set
    /** The Host submitted by the current setup flow, retained when its form closes. */
    var setupHostId by mutableStateOf<String?>(null)
        private set

    private var setupRevision = 0

    fun startAddingHost(method: HostAddMethod = HostAddMethod.MANUAL) {
        pairingReview.clear()
        if (screen != Screen.Hosts) showHosts()
        addedHost = null
        setupHostId = null
        setupRevision++
        hostAddMethod = method
    }

    fun closeAddingHost() { pairingReview.clear(); setupRevision++; hostAddMethod = null; addedHost = null }

    /** The open Host's connection and structure watch, while [Screen.Browse] shows (the latest line after a reconnect). */
    private var open: Pair<HostConnection, StructureWatch?>? = null

    /** The open Host's line, which reconnects on its own; see [HostLink]. */
    private var browseLink: HostLink? = null

    var quickOpenVisible by mutableStateOf(false)
        private set
    private var lastKnownStates by mutableStateOf<List<io.github.shiroel.aries.core.LastKnownState>>(emptyList())
    private var lastKnownWatch: Job? = null
    private var pendingQuickOpen: QuickOpenResult? = null

    private fun quickOpenBase(route: Screen = screen): Screen = when (route) {
        is Screen.Chat -> quickOpenBase(route.returnTo)
        is Screen.Terminal -> quickOpenBase(route.returnTo)
        else -> route
    }

    val quickOpenBrowser: HostBrowser? get() = (quickOpenBase() as? Screen.Browse)?.browser

    /** The live list under detail; layout changes never create another list or connection. */
    val listScreen: Screen? get() = when (val route = quickOpenBase()) {
        Screen.Hosts, is Screen.Browse, is Screen.Agents -> route
        // Keep an available navigation list in the tablet pane during setup
        // and Settings, independent of the destination's connection ownership.
        is Screen.Connecting, Screen.Settings, is Screen.JumpHosts -> Screen.Hosts
        else -> null
    }

    /** Replace detail from the visible list, retaining its original navigation/connection owner. */
    fun selectListedAgent(board: AgentBoard, entry: AgentEntry) {
        val list = listScreen as? Screen.Agents ?: return
        if (list.board !== board || board.find(entry.key) == null || currentAgentKey == entry.key) return
        openAgentTarget(entry.host, entry.terminal.address)
    }

    fun selectListedTerminal(browser: HostBrowser, terminal: TerminalInfo) {
        val list = listScreen as? Screen.Browse ?: return
        if (list.browser !== browser) return
        val current = browser.findTerminal(terminal.address) ?: return
        if (currentAgentKey == AgentKey(browser.host.id, current.address)) return
        returnToQuickOpenBase()
        openTerminal(current)
    }

    /** No connections are started by search. Existing live watches take precedence per Host. */
    val quickOpenSources: List<QuickOpenSource>
        get() {
            val live = (agentHome?.board?.quickOpenSources.orEmpty() + when (val base = quickOpenBase()) {
                is Screen.Browse -> listOfNotNull(base.browser.takeIf { it.link?.state == LinkState.Up && it.hasTerminalList && it.hasLiveTerminals }?.let {
                    QuickOpenSource(it.host, it.structure, it.agents.map { entry -> entry.terminal })
                })
                is Screen.Agents -> base.board.quickOpenSources
                else -> emptyList()
            }).associateBy { it.host.id }
            val stored = lastKnownStates.associateBy { it.hostId }
            return hosts.map { host ->
                live[host.id] ?: stored[host.id].let { state ->
                    QuickOpenSource(host, state?.structure, state?.agents.orEmpty(), state?.fetchedAtMs,
                        state?.structureFetchedAtMs ?: state?.fetchedAtMs, live = false)
                }
            }
        }

    fun openQuickOpen() {
        userNavigation()
        closeCallInbox()
        guard { lastKnownStates = core.lastKnownStates() }
        quickOpenVisible = true
        observeLastKnownStates()
    }

    /** One foreground subscription feeds search, home and drawer; it never starts SSH. */
    private fun observeLastKnownStates() {
        if (lastKnownWatch?.isActive == true) return
        lastKnownWatch = scope.launch {
            try {
                io.github.shiroel.aries.LastKnownStates.observe(core).collect { lastKnownStates = it }
            } catch (_: CoreException) {
                // Keep the readable stored values if the subscription fails.
            }
        }
    }

    fun closeQuickOpen() {
        quickOpenVisible = false
    }

    private fun returnToQuickOpenBase() {
        while (screen is Screen.Terminal || screen is Screen.Chat) {
            if (screen is Screen.Terminal) closeTerminal() else closeChat()
        }
    }

    fun selectQuickOpen(result: QuickOpenResult) {
        userNavigation()
        closeQuickOpen()
        val browser = quickOpenBrowser
        if (browser?.host?.id == result.host.id && browser.link?.state == LinkState.Up) {
            returnToQuickOpenBase()
            revealQuickOpen(browser, result)
        } else {
            back()
            pendingQuickOpen = result
            connect(result.host)
        }
    }

    /** Revalidate IDs on the new live structure; never fall back to a similarly named target. */
    private fun revealQuickOpen(browser: HostBrowser, result: QuickOpenResult) {
        val structure = browser.structure ?: return
        pendingQuickOpen = null
        val space = structure.spaces.find { it.id == result.spaceId }
        val tab = space?.tabs?.find { it.id == result.tabId }
        val terminal = result.address?.let(browser::findTerminal)?.takeIf { it.state == "live" }
        val exists = when (result.kind) {
            QuickOpenKind.HOST -> true
            QuickOpenKind.SPACE -> space != null
            QuickOpenKind.TAB -> tab != null
            QuickOpenKind.AGENT -> terminal != null
        }
        if (!exists) {
            prompt = Prompt.Problem(Message(R.string.quick_open_title), Message(R.string.quick_open_gone, listOf(result.name)))
            return
        }
        browser.closeSpace()
        if (space != null) browser.openSpace(space.id)
        if (tab != null) browser.selectTab(tab)
        if (terminal != null) openTerminal(terminal)
    }

    private fun quickOpenTerminal(): TerminalInfo? = when (val route = screen) {
        is Screen.Terminal -> route.viewer.terminal
        is Screen.Chat -> route.chat.terminal
        else -> null
    }

    @StringRes
    fun quickOpenCommandReason(command: QuickOpenCommand): Int? {
        if (command == QuickOpenCommand.SETTINGS) return null
        val browser = quickOpenBrowser ?: return R.string.quick_open_choose_host
        if (browser.link?.state != LinkState.Up || browser.structure == null) return R.string.structure_connection_closed
        val editor = browser.layoutEditor
        if (editor?.busy == true) return R.string.quick_open_busy
        when (command) {
            QuickOpenCommand.NEW_TAB, QuickOpenCommand.RENAME_SPACE, QuickOpenCommand.CLOSE_SPACE ->
                if (browser.openSpace == null) return R.string.quick_open_choose_space
            QuickOpenCommand.RENAME_TAB, QuickOpenCommand.CLOSE_TAB ->
                if (browser.tab == null) return R.string.quick_open_choose_tab
            QuickOpenCommand.END_TERMINAL -> if (quickOpenTerminal() == null) return R.string.quick_open_choose_terminal
            else -> Unit
        }
        if (command != QuickOpenCommand.RUN_AGENT && command != QuickOpenCommand.END_TERMINAL && editor?.available != true)
            return R.string.layout_app_unavailable
        return null
    }

    fun runQuickOpenCommand(command: QuickOpenCommand) {
        if (quickOpenCommandReason(command) != null) return
        closeQuickOpen()
        if (command == QuickOpenCommand.SETTINGS) { openSettings(); return }
        val terminal = quickOpenTerminal()
        returnToQuickOpenBase()
        val browser = quickOpenBrowser ?: return
        if (command == QuickOpenCommand.RUN_AGENT) { newTerminal(browser); return }
        val editor = browser.layoutEditor ?: return
        val space = browser.openSpace
        val tab = browser.tab
        when (command) {
            QuickOpenCommand.NEW_SPACE -> editor.naming = LayoutNameForm(LayoutNameKind.NEW_SPACE)
            QuickOpenCommand.NEW_TAB -> editor.naming = LayoutNameForm(LayoutNameKind.NEW_TAB, space!!.id, folder = space.folder.orEmpty())
            QuickOpenCommand.RENAME_SPACE -> editor.naming = LayoutNameForm(LayoutNameKind.RENAME_SPACE, space!!.id, if (space.named) space.name else "")
            QuickOpenCommand.RENAME_TAB -> editor.naming = LayoutNameForm(LayoutNameKind.RENAME_TAB, tab!!.id, if (tab.named) tab.name else "")
            QuickOpenCommand.CLOSE_SPACE -> editor.ending = LayoutEndTarget.Space(space!!.id)
            QuickOpenCommand.CLOSE_TAB -> editor.ending = LayoutEndTarget.Tab(tab!!.id)
            QuickOpenCommand.END_TERMINAL -> editor.ending = LayoutEndTarget.Terminal(terminal!!)
        }
    }

    private var backgroundRelease: Job? = null
    private var screensPaused = false
    private var pendingNetworkChange = false
    private val screensActive = MutableStateFlow(true)
    private val foregroundPulls = mutableSetOf<Job>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun connectScreen(hostId: String): HostConnection = screensActive.transformLatest { active ->
        if (active) emit(withContext(keyWork) { core.connect(hostId) })
    }.first()

    private fun link(host: Host, conn: HostConnection?, onDown: (CoreException) -> Unit = {}) = HostLink(
        host, conn, scope,
        connect = { connectScreen(host.id) },
        onDown = onDown,
        onSettings = { error ->
            when (error) {
                is CoreException.HostKeyUnknown -> prompt = Prompt.TrustHostKey(host, error.fingerprint)
                is CoreException.JumpHostKeyUnknown -> prompt = Prompt.TrustJumpHostKey(host, error.hop, error.address, error.fingerprint)
                is CoreException.KeyInvalidated -> openKeyRecovery()
                is CoreException.NoKey, is CoreException.KeyLocked -> openKeySettings()
                else -> openHostSettings(host)
            }
        },
    ).also { if (screensPaused) it.pause() }


    /** Every line the screens use now: the open Host's and the Agent list's. */
    private val links: List<HostLink> get() = (listOfNotNull(browseLink, callLink, agentOpeningLink) + agentLinks.values).distinct()

    /** Prefer a usable line over an earlier owner waiting for recovery. */
    private fun upLink(hostId: String): HostLink? = links.firstOrNull {
        it.host.id == hostId && it.state == LinkState.Up && it.connection != null
    }

    private fun preferredLink(hostId: String): HostLink? = upLink(hostId)
        ?: links.firstOrNull { it.host.id == hostId && it.state == LinkState.Reconnecting }
        ?: links.firstOrNull { it.host.id == hostId && it.state is LinkState.Down }

    internal fun hasHostLink(hostId: String): Boolean = links.any { it.host.id == hostId }

    /**
     * The phone's network changed (Wi-Fi to LTE, airplane mode): replace every
     * line and restore the screens. Each line reconnects once, on its own path:
     * Host connections through their [HostLink], the open Chat's stream (its
     * own line, see `ChatWatch`) through [ChatModel.reconnect].
     */
    fun networkChanged() {
        if (screensPaused) {
            pendingNetworkChange = true
            return
        }
        links.forEach { it.networkChanged() }
        liveChat?.reconnect()
    }

    /** Retain warm screen streams briefly; App Lock can request immediate release. */
    fun backgrounded(retainConnections: Boolean = true, connectionGraceMs: Long = SCREEN_CONNECTION_GRACE_MS) {
        if (screensPaused && retainConnections) return
        screensPaused = true
        screensActive.value = false
        foregroundPulls.toList().forEach { it.cancel() }
        links.forEach { it.suspendRetries() }
        backgroundRelease?.cancel()
        if (retainConnections) {
            backgroundRelease = scope.launch {
                delay(connectionGraceMs)
                releaseScreenConnections()
            }
        } else releaseScreenConnections()
    }

    private fun releaseScreenConnections() {
        // Give submitted Chat messages to the core before retiring their SSH line.
        liveChat?.pause()
        links.forEach { it.pause() }
    }

    fun resumeScreens() {
        backgroundRelease?.cancel()
        backgroundRelease = null
        screensPaused = false
        screensActive.value = true
        links.forEach { it.resume() }
        liveChat?.resume()
        if (pendingNetworkChange) {
            pendingNetworkChange = false
            networkChanged()
        }
    }

    fun foregrounded() {
        resumeScreens()
        links.forEach { it.check() }
        liveChat?.retryNow()
        val startupHost = startupHostId
        startupHostId = null
        // Live connections publish their lists in the core. Other Hosts
        // get a short pull while the foreground biometric prompt is available.
        // Startup already opens its target, including a biometric prompt if needed.
        hosts.filter { host -> host.id != startupHost && links.none { it.host.id == host.id } }
            .forEach { refreshLastKnownState(it.id) }
    }

    /** Foreground refresh, including biometric Hosts; surfaces use WorkManager instead. */
    fun refreshLastKnownState(hostId: String) {
        if (screensPaused) return
        val pull = scope.launch {
            try {
                val connection = links.firstOrNull { it.host.id == hostId }?.connection
                withContext(keyWork) {
                    if (connection != null) connection.terminals() else core.snapshot(hostId)
                }
            } catch (_: CoreException) {
                // Keep the previous value and its age on a failed pull.
            }
        }
        foregroundPulls += pull
        pull.invokeOnCompletion { foregroundPulls -= pull }
    }

    /** The Chat that is open, or still running under the Terminal opened from it. */
    private val liveChat: ChatModel?
        get() = (ownedScreen as? Screen.Chat ?: (ownedScreen as? Screen.Terminal)?.returnTo as? Screen.Chat)?.chat

    internal var started by mutableStateOf(false)
        private set
    private var explicitSurfaceEntry = false
    private var startupHostId: String? = null
    private var restoring by mutableStateOf(false)
    private var navigationVersion = 0L
    private var suppressLocationSave by mutableStateOf(false)

    /** Only explicit destination changes dismiss navigation overlays. */
    internal var overlayNavigationVersion by mutableStateOf(0L)
        private set

    private fun userNavigation() {
        // Recreation reuses these owners; an explicit destination change ends their overlays.
        conversations.values.forEach { chat ->
            chat.dialogs.dismiss()
            chat.waitingSheetOpen = false
            chat.waitingSheetTarget = null
        }
        agentHome?.board?.needsQueue = null
        overlayNavigationVersion++
        navigationIntent()
    }

    /** Overlay entry and pending opens supersede restoration without dismissing themselves. */
    private fun navigationIntent() {
        navigationVersion++
        suppressLocationSave = false
        restoring = false
        cancelAgentOpen()
    }

    /** Home controls cancel cold-start focus before an asynchronous list can replace the UI. */
    internal fun interactWithHome() {
        if (screen is Screen.Agents) userNavigation()
    }

    /** Called after the activity resumes and installs its biometric authorizer. */
    fun start(target: PushTarget? = null, layout: AppLayout = AppLayout.CONSOLE) {
        if (started) {
            target?.let(::openNotificationTarget)
            return
        }
        started = true
        val saved = locationStore?.load()
        val settings = pendingSettingsState
        pendingSettingsState = null
        if (target != null) {
            startupHostId = target.hostId
            openNotificationTarget(target)
        } else if (!explicitSurfaceEntry && screen == Screen.Hosts) {
            if (settings != null) applySettingsState(settings)
            else if (layout == AppLayout.THREAD) restoreThreadHome()
            else hosts.find { it.id == saved?.hostId }?.let {
                startupHostId = it.id
                connect(it, saved)
            }
        }
        if (locationStore != null) scope.launch {
            snapshotFlow { if (restoring || suppressLocationSave) null else lastLocation() }
                .filterNotNull().collect { location ->
                    locationStore.save(location)
                    val agent = liveChat != null || (ownedScreen as? Screen.Terminal)?.viewer?.terminal?.agentStatus != null
                    if (agent && location.terminal != null) locationStore.saveAgent(location)
                }
        }
    }

    private fun lastLocation(): LastLocation? = settingsColdReturn?.location ?: when (val s = ownedScreen) {
        Screen.Hosts, Screen.Settings -> LastLocation()
        is Screen.Agents -> LastLocation()
        is Screen.Browse -> LastLocation(s.browser.host.id, s.browser.openSpaceId, s.browser.tab?.id)
        is Screen.Terminal -> s.viewer.link?.host?.let { host ->
            terminalLocation(host.id, s.viewer.terminal.address, AgentView.TERMINAL, s.returnTo)
        }
        is Screen.Chat -> terminalLocation(s.chat.host.id, s.chat.terminal.address, AgentView.CHAT, s.returnTo)
        else -> null
    }

    private fun browserOf(origin: Screen): HostBrowser? = when (origin) {
        is Screen.Browse -> origin.browser
        is Screen.Chat -> browserOf(origin.returnTo)
        is Screen.Terminal -> browserOf(origin.returnTo)
        else -> null
    }

    private fun structureOf(origin: Screen, hostId: String): HostStructure? = when (origin) {
        is Screen.Browse -> origin.browser.takeIf { it.host.id == hostId }?.structure
        is Screen.Agents -> origin.board.structureOf(hostId)
        is Screen.Chat -> structureOf(origin.returnTo, hostId)
        is Screen.Terminal -> structureOf(origin.returnTo, hostId)
        else -> null
    }

    private fun terminalLocation(hostId: String, address: String, view: AgentView, origin: Screen): LastLocation {
        val browser = browserOf(origin)?.takeIf { it.host.id == hostId }
        val space = structureOf(origin, hostId)?.spaces?.find { space ->
            space.tabs.any { tab -> tab.panes.any { pane ->
                pane.terminalAddress == address || pane.subAgents.any { it.address == address }
            } }
        }
        val tab = space?.tabs?.find { tab -> tab.panes.any { pane ->
            pane.terminalAddress == address || pane.subAgents.any { it.address == address }
        } }
        return LastLocation(hostId, space?.id ?: browser?.openSpaceId, tab?.id ?: browser?.tab?.id, address, view)
    }

    init {
        guard {
            key = core.publicKey()
            keyProtection = core.keyProtection()
            hosts = core.hosts()
            lastKnownStates = core.lastKnownStates()
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

    var recoveringKeys by mutableStateOf(false)
        private set

    fun openKeyRecovery() {
        dismiss()
        openKeySettings()
        recoveringKeys = true
    }

    internal var generatingKeyId by mutableStateOf<String?>(null)
    var recoveringKeyId by mutableStateOf<String?>(null)
        private set
    var keyRecoveryProblem by mutableStateOf<Message?>(null)
        private set

    internal fun confirmKeyRecovery() {
        val id = generatingKeyId ?: return
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { recoverKey(id) } catch (_: CoreException) { /* Retained below the key list. */ }
        }
    }

    suspend fun recoverKey(keyId: String, privateKey: String? = null, passphrase: String = "") {
        if (recoveringKeyId != null) return
        recoveringKeyId = keyId
        keyRecoveryProblem = null
        modelOperation {
            try {
                withContext(keyWork) { core.recoverKey(keyId, privateKey, passphrase) }
                key = core.publicKey()
                keyProtection = core.keyProtection()
                keys = core.keys()
            } catch (e: CoreException) {
                keyRecoveryProblem = problemFor(e).body
                throw e
            } finally {
                generatingKeyId = null
                recoveringKeyId = null
            }
        }
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

    /** Import/decryption and vault work can block, including biometric authorization. */
    suspend fun importKey(label: String, privateKey: String, passphrase: String) = modelOperation {
        withContext(keyWork) { core.importKey(label.trim(), privateKey, passphrase) }
        key = core.publicKey()
        keyProtection = core.keyProtection()
        keys = core.keys()
    }

    fun editJumps(host: Host) = guard {
        screen = Screen.JumpHosts(host, core.jumpHosts(host.id), core.hostSshKey(host.id))
    }

    fun saveJumps(host: Host, jumps: List<JumpHost>, hostKeyId: String?) = guard {
        core.setJumpHosts(host.id, jumps)
        core.setHostSshKey(host.id, hostKeyId)
        refreshRoutes()
        loadHostSettings(hosts.first { it.id == host.id })
        screen = Screen.Settings
    }

    private fun refreshRoutes() {
        keys = core.keys()
        jumps = hosts.associate { it.id to core.jumpHosts(it.id) }
    }

    fun addHost(name: String, address: String, port: Int, user: String, connectAfterAdd: Boolean = false): Boolean {
        return try {
            val label = name.trim().ifBlank { address.trim() }
            val host = core.addHost(label, Endpoint.Ssh(address.trim(), port.toUShort(), user.trim()))
            hosts = core.hosts()
            refreshRoutes()
            hostsChanged()
            if (hostAddMethod != null) { addedHost = host; setupHostId = host.id }
            if (connectAfterAdd) connect(host)
            true
        } catch (e: CoreException) {
            prompt = problemFor(e)
            false
        }
    }

    /** Submitted writes and the refresh after them belong to the ViewModel, not a form's lifetime. */
    private suspend fun <T> modelOperation(write: suspend () -> T): T =
        scope.async { runCatching { write() } }.await().getOrThrow()

    fun inspectPairingQr(qr: String) = core.inspectPairingQr(qr)

    internal fun pairReviewedHost(connectAfterPair: Boolean = false) {
        val review = pairingReview
        if (review.busy) return
        val credential = review.qr ?: return
        val name = review.name
        val revision = review.revision
        // Consume locally before dispatching; a lost reply never re-enables this credential.
        review.qr = null
        review.busy = true
        review.error = null
        scope.launch {
            try {
                val host = pairHostQr(name, credential, connectAfterPair)
                if (review.revision == revision) {
                    review.details = null
                    review.name = ""
                    review.paired = host
                }
            } catch (e: CoreException) {
                if (review.revision == revision) review.error = when (e) {
                    is CoreException.InvalidPairingQr -> R.string.host_pair_invalid
                    is CoreException.HostKeyChanged -> R.string.host_pair_key_changed
                    else -> R.string.host_pair_refused
                }
            } finally {
                if (review.revision == revision) review.busy = false
            }
        }
    }

    suspend fun pairHostQr(name: String, qr: String, connectAfterPair: Boolean = false): Host = modelOperation {
        val origin = screen
        val revision = setupRevision
        val host = withContext(keyWork) { core.pairHostQr(name, qr) }
        hosts = core.hosts()
        refreshRoutes()
        hostsChanged()
        if (connectAfterPair && screen === origin && setupRevision == revision && hostAddMethod == HostAddMethod.QR) {
            setupHostId = host.id
            connect(host)
        }
        host
    }

    /** Saves in place; the core closes old lines only when the endpoint changed. */
    suspend fun saveHost(host: Host, name: String, address: String, port: Int, user: String): Boolean = modelOperation {
        try {
            val previous = hosts.firstOrNull { it.id == host.id } ?: host
            val endpoint = Endpoint.Ssh(address.trim(), port.toUShort(), user.trim())
            withContext(keyWork) {
                core.editHost(host.id, name.trim().ifBlank { address.trim() }, endpoint)
            }
            hosts = core.hosts()
            refreshRoutes()
            val saved = hosts.first { it.id == host.id }
            if (settingsHost?.id == host.id) settingsHost = saved
            if (previous.endpoint != endpoint) {
                calls.stopAll()
                loadHostSettings(saved)
            }
            hostsChanged()
            true
        } catch (e: CoreException) {
            prompt = problemFor(e)
            false
        }
    }

    fun connect(host: Host) = connect(host, null)

    private fun connect(host: Host, restore: LastLocation?, savedLaunchId: String? = null, confirmSavedLaunch: Boolean = true,
        restoreTerminalParent: String? = null) {
        closeAddingHost()
        val quickOpen = pendingQuickOpen
        val taskHostId = newTaskHostId
        val taskSelection = threadTaskSelection
        showHosts()
        pendingQuickOpen = quickOpen
        newTaskHostId = taskHostId
        threadTaskSelection = taskSelection
        suppressLocationSave = false
        restoring = restore != null
        missingKeyHostId = null
        val link = upLink(host.id) ?: link(host, null) { error ->
            scope.launch { initialConnectionProblem(host, error, restore != null) }
        }
        browseLink = link
        val stored = try { core.lastKnownState(host.id) } catch (_: CoreException) { null }
        val cachedBrowser = stored?.structure?.let { structure ->
            HostBrowser(host, ::userNavigation).also { browser ->
                browser.link = link
                browser.update(structure)
                stored.agents.forEach(browser::replaceTerminal)
            }
        }
        val connecting = Screen.Connecting(host, link, cachedBrowser)
        screen = connecting
        status = scope.launch {
            link.keep { conn, _ ->
                if (ownedScreen !== connecting) return@keep

                val browser = cachedBrowser ?: HostBrowser(host, ::userNavigation)
                browser.layoutEditor = LayoutEditor(browser, scope)
                browser.link = link
                browser.savedLaunches = conn.savedLaunches()
                open = conn to null
                val next = Screen.Browse(browser)
                if (settingsReturnTo === connecting) settingsReturnTo = next else screen = next
                loadHostSettings(host)
                status = watchBrowse(link, browser, restore.takeIf { restoring }, savedLaunchId, confirmSavedLaunch, restoreTerminalParent)
            }
        }
    }

    /** Host-list task entry waits for connection and the first structure snapshot. */
    var newTaskHostId by mutableStateOf<String?>(null)

    fun newTask(host: Host) {
        newTaskHostId = host.id
        connect(host)
    }

    // Drafts and mutation jobs belong to the Activity's retained HostsModel owner scope.
    private data class ThreadTaskSelection(val hostId: String, val input: androidx.compose.ui.text.input.TextFieldValue, val showDialog: Boolean)
    private var threadTaskSelection: ThreadTaskSelection? = null
    private val tasks = mutableMapOf<String, NewTask>()
    internal var task by mutableStateOf<NewTask?>(null)
        private set
    internal var taskVisible by mutableStateOf(false)
        private set
    private val taskJobs = mutableMapOf<NewTask, Job>()

    internal fun prepareTask(link: HostLink, cwd: String? = null, prompt: String? = null, showDialog: Boolean = true) {
        if (link.state != LinkState.Up || pendingLaunch(link.host.id) || pendingWorktree(link.host.id)) return
        val conn = link.connection ?: return
        val selection = threadTaskSelection?.takeIf { it.hostId == link.host.id }
        val previous = tasks[link.host.id]
        if (previous != null && (prompt == null || previous.form.busy || previous.launchUnknown || previous.deliveryUnknown || previous.terminal != null)) {
            if (previous.link !== link && previous.waiting) taskJobs[previous]?.cancel()
            previous.link = link
            task = previous
            taskVisible = selection?.showDialog ?: showDialog
            if (selection != null) {
                if (!previous.form.busy && previous.terminal == null && !previous.launchUnknown && !previous.deliveryUnknown) {
                    previous.form.cwd = ""
                    previous.input = selection.input
                }
                threadTaskSelection = null
            }
            return
        }
        val browser = quickOpenBrowser?.takeIf { it.host.id == link.host.id }
        val structure = browser?.structure ?: (screen as? Screen.Agents)?.board?.structureOf(link.host.id)
            ?: HostStructure(emptyList(), emptyList(), null)
        val form = LaunchForm(structure, conn.recentCwds(), null, null, conn.savedLaunches())
        form.preset = AgentPreset.CLAUDE_CODE
        val terminal = quickOpenTerminal()?.takeIf { currentAgentKey?.hostId == link.host.id }
        form.cwd = if (selection != null) "" else cwd ?: terminal?.takeIf { it.machine == null }?.currentCwd ?:
            browser?.openSpace?.folder ?: conn.recentCwds().firstOrNull() ?: ""
        val created = NewTask(link, form, prompt.orEmpty())
        selection?.let { created.input = it.input }
        tasks[link.host.id] = created
        task = created
        taskVisible = selection?.showDialog ?: showDialog
        if (selection != null) threadTaskSelection = null
        if (form.cwd.isBlank() && selection == null) scope.launch {
            try {
                val home = conn.machineExec(listOf("sh", "-c", "printf '%s' \"\$HOME\""), null, 5u)
                val path = home.stdout.toString(Charsets.UTF_8)
                if (home.code == 0 && !home.truncated && !home.timedOut && path.startsWith('/') && form.cwd.isBlank()) form.cwd = path
            } catch (_: CoreException) { /* The folder helper and picker remain available. */ }
        }
    }

    internal fun startTask() = startTaskFromSurface(inline = false)

    internal fun startInlineTask() {
        interactWithHome()
        startTaskFromSurface(inline = true)
    }

    private fun startTaskFromSurface(inline: Boolean) {
        val origin = screen
        val current = task ?: return
        if (!current.canStart) return
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            current.start { terminal, draft ->
                if (task === current && (taskVisible || inline && screen === origin) && current.link in links) {
                    taskVisible = false
                    tasks.remove(current.link.host.id)
                    task = null
                    openTaskChat(current.link, terminal, draft)
                }
            }
        }
        taskJobs[current] = job
        job.invokeOnCompletion { taskJobs.remove(current) }
    }

    internal fun dismissTask() {
        taskVisible = false
        // Dismissal cancels only the readiness read. Launch and send outcomes must finish once.
        task?.takeIf { it.waiting }?.let { taskJobs[it]?.cancel() }
    }

    /** Retire only the tracking record. The uncertain remote launch is never replayed. */
    internal fun stopTrackingTask() {
        val current = task ?: return
        if (current.form.busy || !current.launchUnknown) return
        tasks.remove(current.link.host.id)
        taskVisible = false
        task = null
    }

    internal fun openCreatedTask() {
        val current = task ?: return
        val terminal = current.terminal ?: return
        if (current.form.busy) return
        taskVisible = false
        tasks.remove(current.link.host.id)
        task = null
        openTaskChat(current.link, terminal, if (current.sent) "" else current.prompt)
    }

    /** New tasks always open Chat, independently of the user's last view preference. */
    internal fun openTaskChat(link: HostLink, terminal: TerminalInfo, draft: String) {
        val origin = when (val current = screen) {
            is Screen.Chat -> { current.chat.stop(); current.returnTo }
            is Screen.Terminal -> {
                viewing?.cancel()
                viewing = null
                (current.returnTo as? Screen.Chat)?.let { it.chat.stop(); it.returnTo } ?: current.returnTo
            }
            else -> current
        }
        chat(link, terminal, origin, draft)
    }

    /** First-open trust and installation still use their existing, explicit UI. */
    private suspend fun initialConnectionProblem(host: Host, error: CoreException, restoringLocation: Boolean) {
        val connecting = screen as? Screen.Connecting ?: return
        if (connecting.host.id != host.id || (connecting.link.state as? LinkState.Down)?.error !== error) return
        // Cancelling startup biometric authorization returns to the Host list.
        if (restoringLocation && error is CoreException.KeyLocked) {
            back()
            return
        }
        if (error is CoreException.NoKey && key == null) {
            back()
            missingKeyHostId = host.id
            return
        }
        val canNeedCore = error is CoreException.HerdrCoreMissing || error is CoreException.Protocol ||
            (error is CoreException.Incompatible && error.reason is Incompatibility.CoreTooOld)
        val needsCore = host.endpoint is Endpoint.Ssh && (error is CoreException.HerdrCoreMissing || canNeedCore && try {
            when (val state = withContext(keyWork) { core.herdrCoreStatus(host.id) }) {
                HerdrCoreState.Missing -> true
                is HerdrCoreState.Installed -> state.needsUpdate
            }
        } catch (_: CoreException) { false })
        if (screen !== connecting) return
        if (needsCore) {
            openHostSettings(host, Message(R.string.herdr_install_connect_reason))
            return
        }
        when (error) {
            is CoreException.HostKeyUnknown -> prompt = Prompt.TrustHostKey(host, error.fingerprint)
            is CoreException.JumpHostKeyUnknown -> prompt = Prompt.TrustJumpHostKey(host, error.hop, error.address, error.fingerprint)
            else -> Unit // The shared notice owns errors and recovery actions.
        }
    }

    internal class WorktreeFlow(var browser: HostBrowser, val form: WorktreeForm)
    private val worktrees = mutableMapOf<String, WorktreeFlow>()
    internal var activeWorktree by mutableStateOf<WorktreeFlow?>(null)
        private set

    private fun pendingWorktree(hostId: String): Boolean {
        val flow = worktrees[hostId] ?: return false
        if (!flow.form.busy && !flow.form.openUnknown && flow.form.openedAddress == null) return false
        if (!flow.form.busy) quickOpenBrowser?.takeIf { it.host.id == hostId }?.let { flow.browser = it }
        activeWorktree = flow
        return true
    }

    internal fun showWorktrees(browser: HostBrowser, cwd: String, spaceId: String?) {
        if (pendingTask(browser.host.id) || pendingLaunch(browser.host.id) || pendingWorktree(browser.host.id)) return
        val flow = WorktreeFlow(browser, WorktreeForm(cwd, spaceId))
        activeWorktree = flow
        worktrees[browser.host.id] = flow
        loadWorktrees()
    }

    internal fun dismissWorktrees() { activeWorktree = null }

    internal fun loadWorktrees() {
        val flow = activeWorktree ?: return
        val conn = flow.browser.link?.connection ?: return
        val form = flow.form
        if (form.busy) return
        form.busy = true
        form.error = null
        scope.launch {
            try {
                form.entries = conn.listWorktrees(form.cwd)
                form.selected = form.entries.find { it.path == form.selected?.path }
            } catch (e: CoreException) { form.error = problemFor(e).body
            } finally { form.busy = false }
        }
    }

    internal fun openWorktree(again: Boolean = false) {
        val flow = activeWorktree ?: return
        val browser = flow.browser
        val form = flow.form
        val link = browser.link ?: return
        val conn = link.connection ?: return
        val selected = form.selected ?: return
        if (form.busy || (form.openUnknown && !again)) return
        form.busy = true
        form.error = null
        scope.launch {
            try {
                if (form.openedAddress == null) {
                    form.openUnknown = true
                    form.openedAddress = when (val opened = conn.openWorktree(selected.path, form.spaceId, form.preset)) {
                        is io.github.shiroel.aries.core.WorktreeOpen.Tab -> {
                            browser.reveal(opened.spaceId, opened.tabId, opened.address)
                            opened.address
                        }
                        is io.github.shiroel.aries.core.WorktreeOpen.Terminal -> opened.address
                    }
                    form.openUnknown = false
                }
                // The creation RPC has completed; only this read may reconnect.
                val terminal = withTimeoutOrNull(30_000) {
                    link.keep { current, _ -> current.terminals().find { it.address == form.openedAddress } }
                }
                if (terminal == null) {
                    form.error = Message(R.string.launch_connection_closed)
                    return@launch
                }
                worktrees.remove(browser.host.id)
                if (activeWorktree === flow) activeWorktree = null
                if ((quickOpenBase() as? Screen.Browse)?.browser === browser) {
                    returnToQuickOpenBase()
                    observe(link, terminal, Screen.Browse(browser))
                }
            } catch (e: CoreException) {
                if (e is CoreException.Rpc && e.kind == "spawn_failed") form.openUnknown = false
                form.error = if (form.openUnknown) null else problemFor(e).body
            } finally { form.busy = false }
        }
    }

    internal class LaunchFlow(var link: HostLink, val form: LaunchForm, var browser: HostBrowser?, var origin: Screen,
        var storedRequest: io.github.shiroel.aries.core.LaunchRequest? = null)
    private val launches = mutableMapOf<String, LaunchFlow>()
    internal var activeLaunch by mutableStateOf<LaunchFlow?>(null)
        private set

    private fun pendingTask(hostId: String): Boolean {
        val pending = tasks[hostId] ?: return false
        if (!pending.form.busy && !pending.launchUnknown && !pending.deliveryUnknown && pending.terminal == null) return false
        links.firstOrNull { it.host.id == hostId && it.state == LinkState.Up }?.let { link ->
            if (pending.link !== link && pending.waiting) taskJobs[pending]?.cancel()
            pending.link = link
        }
        task = pending
        taskVisible = true
        return true
    }

    private fun pendingLaunch(hostId: String): Boolean {
        val flow = launches[hostId] ?: return false
        if (!flow.form.busy && !flow.form.launchUnknown) return false
        if (!flow.form.busy) links.firstOrNull { it.host.id == hostId && it.state == LinkState.Up }?.let { link ->
            flow.link = link
            flow.browser = quickOpenBrowser?.takeIf { it.host.id == hostId }
            flow.origin = screen
        }
        activeLaunch = flow
        return true
    }

    /** Opens the retained launch operation when this Host already has an unresolved request. */
    fun newTerminal(browser: HostBrowser) {
        if (pendingTask(browser.host.id) || pendingLaunch(browser.host.id) || pendingWorktree(browser.host.id)) return
        val conn = browser.link?.connection ?: return
        val structure = browser.structure ?: return
        val form = LaunchForm(structure, conn.recentCwds(), browser.openSpaceId, browser.tab?.id, browser.savedLaunches)
        activeLaunch = LaunchFlow(browser.link ?: return, form, browser, screen).also { launches[browser.host.id] = it }
    }

    private fun storeLaunch(link: HostLink, browser: HostBrowser?, form: LaunchForm, saved: SavedLaunch) {
        val conn = link.connection ?: return
        if (form.busy) return
        form.error = null
        try {
            form.stored(conn.saveLaunch(saved))
            browser?.savedLaunches = form.saved.toList()
            onSavedLaunchesChanged()
        } catch (e: CoreException) { form.error = launchProblem(e) }
    }

    internal fun saveActiveLaunch() {
        val flow = activeLaunch ?: return
        storeLaunch(flow.link, flow.browser, flow.form, flow.form.toSaved())
    }

    internal fun undoLaunchDelete() {
        val flow = activeLaunch ?: return
        val saved = flow.form.removedLaunch ?: return
        storeLaunch(flow.link, flow.browser, flow.form, saved)
        if (flow.form.error == null) flow.form.removedLaunch = null
    }

    private fun deleteLaunch(link: HostLink, browser: HostBrowser?, form: LaunchForm, saved: SavedLaunch) {
        val conn = link.connection ?: return
        if (form.busy) return
        form.error = null
        try {
            conn.deleteSavedLaunch(saved.id)
            form.removed(saved.id)
            form.removedLaunch = saved
            browser?.savedLaunches = form.saved.toList()
            onSavedLaunchesChanged()
        } catch (e: CoreException) { form.error = launchProblem(e) }
    }

    internal fun deleteActiveLaunch(saved: SavedLaunch) {
        val flow = activeLaunch ?: return
        deleteLaunch(flow.link, flow.browser, flow.form, saved)
    }

    fun runSaved(browser: HostBrowser, saved: SavedLaunch) {
        val link = browser.link ?: return
        if (pendingTask(link.host.id) || pendingLaunch(link.host.id) || pendingWorktree(link.host.id)) return
        val conn = link.connection ?: return
        val form = LaunchForm(browser.structure ?: return, conn.recentCwds(), null, null, browser.savedLaunches)
        form.edit(saved)
        val flow = LaunchFlow(link, form, browser, screen, saved.request)
        launches[link.host.id] = flow
        activeLaunch = flow
        submitLaunch(flow)
    }

    internal fun runSavedFromAgents(board: AgentBoard, link: HostLink, saved: SavedLaunch) {
        val origin = screen as? Screen.Agents ?: return
        if (origin.board !== board || board.links[link.host.id] !== link || link.state != LinkState.Up) return
        if (pendingTask(link.host.id) || pendingLaunch(link.host.id) || pendingWorktree(link.host.id)) return
        val conn = link.connection ?: return
        val form = LaunchForm(board.structureOf(link.host.id) ?: HostStructure(emptyList(), emptyList(), null),
            conn.recentCwds(), null, null, conn.savedLaunches())
        form.edit(saved)
        val flow = LaunchFlow(link, form, null, origin, saved.request)
        launches[link.host.id] = flow
        activeLaunch = flow
        submitLaunch(flow)
    }

    internal fun dismissLaunch() { activeLaunch = null }

    internal fun stopTrackingLaunch() {
        val flow = activeLaunch ?: return
        if (flow.form.busy || !flow.form.launchUnknown) return
        launches.remove(flow.link.host.id)
        activeLaunch = null
    }

    internal fun launchActive(again: Boolean = false) {
        val flow = activeLaunch ?: return
        if (again && !flow.form.busy && flow.link.state == LinkState.Up && flow.link.connection != null) {
            flow.form.launchUnknown = false
        }
        submitLaunch(flow)
    }

    private fun submitLaunch(flow: LaunchFlow) {
        val form = flow.form
        if (!form.canLaunch || flow.link.state != LinkState.Up) return
        val conn = flow.link.connection ?: return
        val request = flow.storedRequest ?: form.request()
        form.busy = true // Set before scheduling so a second tap cannot race the job.
        form.error = null
        scope.launch {
            form.launchUnknown = true
            try {
                val terminal = conn.launch(request)
                form.launchUnknown = false
                launches.remove(flow.link.host.id)
                if (activeLaunch === flow) activeLaunch = null
                flow.browser?.reveal(request.spaceId, request.tabId, terminal.address)
                if (flow.link in links) {
                    val base = quickOpenBase()
                    val browser = flow.browser
                    if (browser != null && (base as? Screen.Browse)?.browser === browser) {
                        returnToQuickOpenBase()
                        observe(flow.link, terminal, Screen.Browse(browser))
                    } else if (screen === flow.origin) openTaskChat(flow.link, terminal, "")
                }
            } catch (e: CoreException) {
                if (e is CoreException.Rpc && e.kind == "spawn_failed") {
                    form.launchUnknown = false
                    flow.storedRequest = null
                }
                form.error = if (form.launchUnknown) null else launchProblem(e)
            } finally { form.busy = false }
        }
    }

    fun trust(prompt: Prompt.TrustHostKey) {
        if (this.prompt !== prompt) return
        this.prompt = null
        guard {
            core.trustHostKey(prompt.host.id, prompt.fingerprint)
            hosts = core.hosts()
        }
        val host = hosts.find { it.id == prompt.host.id } ?: return
        when (prompt.then) {
            AfterTrust.Connect -> {
                val waiting = links.filter { it.host.id == host.id && it.state is LinkState.Down }
                val chat = liveChat?.takeIf { it.host.id == host.id && it.connectionError != null }
                if (waiting.isNotEmpty() || chat != null) {
                    waiting.forEach { it.retryNow() }
                    chat?.retryConnection()
                } else connect(host)
            }
            // The password was not kept, so ask for it again.
            AfterTrust.InstallKey -> askPassword(host)
        }
    }

    fun authorizationKey(host: Host): KeyInfo? = keys.find { it.id == (core.hostSshKey(host.id) ?: "default") }

    fun showKeyQr(selected: PublicKeyInfo? = key) = guard {
        val k = selected ?: return@guard
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(k.openssh,
            com.google.zxing.BarcodeFormat.QR_CODE, 1, 1,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 0,
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
        prompt = Prompt.KeyQr(k, QrMatrix(matrix.width.toUInt(),
            (0 until matrix.height).flatMap { y -> (0 until matrix.width).map { x -> matrix[x, y] } }))
    }

    fun askPassword(host: Host) {
        prompt = Prompt.Password(host)
    }

    /** Installs the public key on [host] with a one-time password login. The password is not kept. */
    var installingKeyOn by mutableStateOf<String?>(null)
        private set

    fun installKey(host: Host, password: String) {
        if (installingKeyOn != null) return
        installingKeyOn = host.id
        prompt = null
        scope.launch {
            prompt = try {
                val body = when (withContext(keyWork) { core.installKeyWithPassword(host.id, password) }) {
                    KeyInstall.ADDED -> R.string.key_install_added_body
                    KeyInstall.ALREADY_PRESENT -> R.string.key_install_present_body
                }
                key = core.publicKey()
                keys = core.keys()
                links.filter { it.host.id == host.id && it.state is LinkState.Down }.forEach { it.retryNow() }
                liveChat?.takeIf { it.host.id == host.id && it.connectionError != null }?.retryConnection()
                Prompt.Problem(Message(R.string.key_install_done_title), Message(body, listOf(host.name)))
            } catch (e: CoreException) {
                when (e) {
                    is CoreException.HostKeyUnknown -> Prompt.TrustHostKey(host, e.fingerprint, AfterTrust.InstallKey)
                    is CoreException.JumpHostKeyUnknown ->
                        Prompt.TrustJumpHostKey(host, e.hop, e.address, e.fingerprint, AfterTrust.InstallKey)
                    else -> problemFor(e)
                }
            } finally { installingKeyOn = null }
        }
    }

    fun trust(prompt: Prompt.TrustJumpHostKey) {
        if (this.prompt !== prompt) return
        this.prompt = null
        guard {
            core.trustJumpHostKey(prompt.host.id, prompt.hop, prompt.fingerprint)
            refreshRoutes()
        }
        val host = hosts.find { it.id == prompt.host.id } ?: return
        when (prompt.then) {
            AfterTrust.Connect -> {
                val waiting = links.filter { it.host.id == host.id && it.state is LinkState.Down }
                val chat = liveChat?.takeIf { it.host.id == host.id && it.connectionError != null }
                if (waiting.isNotEmpty() || chat != null) {
                    waiting.forEach { it.retryNow() }
                    chat?.retryConnection()
                } else connect(host)
            }
            AfterTrust.InstallKey -> askPassword(host)
        }
    }

    fun askRemove(host: Host) {
        prompt = Prompt.ConfirmRemove(host)
    }

    fun remove(prompt: Prompt.ConfirmRemove) {
        this.prompt = null
        guard {
            conversations.filterKeys { it.hostId == prompt.host.id }.values.forEach { it.stop() }
            core.removeHost(prompt.host.id)
            if (settingsReturnTo != null) back()
            hostSettings.remove(prompt.host.id)
            conversations.keys.removeAll { it.hostId == prompt.host.id }
            taskChanges.keys.removeAll { it.hostId == prompt.host.id }
            hosts = core.hosts()
            hostsChanged()
            settingsHost = null
            settingsKeys = false
            settingsDiagnostics = false
            screen = Screen.Settings
            flushPushRevocations()
        }
    }

    fun dismiss() {
        val cancelledHost = when (val current = prompt) {
            is Prompt.TrustHostKey -> current.host.takeIf { current.then == AfterTrust.Connect }
            is Prompt.TrustJumpHostKey -> current.host.takeIf { current.then == AfterTrust.Connect }
            else -> null
        }
        pendingQuickOpen = null
        prompt = null
        // Initial trust belongs to the pending connection; cancelling abandons it.
        if (cancelledHost != null && agentOpeningHostId == cancelledHost.id) cancelAgentOpen()
        if (cancelledHost != null && (screen as? Screen.Connecting)?.host?.id == cancelledHost.id) back()
    }

    /** The open Host's structure and live Agent status, on each line [HostLink] brings; cancelled on [back]. */
    private var status: Job? = null

    private fun watchBrowse(link: HostLink, browser: HostBrowser, restore: LastLocation? = null, savedLaunchId: String? = null, confirmSavedLaunch: Boolean = true,
        restoreTerminalParent: String? = null): Job = scope.launch {
        var pendingLaunch = savedLaunchId
        var pending = restore
        val restoreVersion = navigationVersion
        var terminals: List<TerminalInfo>? = null
        fun restoreIfReady() {
            val saved = pending ?: return
            val structure = browser.structure ?: return
            val list = terminals ?: return
            if (browseLink !== link || navigationVersion != restoreVersion ||
                (screen as? Screen.Browse)?.browser !== browser) {
                pending = null
                return
            }
            pending = null
            restoring = false
            val terminal = list.find { it.address == saved.terminal && it.state == "live" }
            val space = structure.spaces.find { space ->
                terminal != null && space.tabs.any { tab -> tab.panes.any { pane ->
                    pane.terminalAddress == terminal.address || pane.subAgents.any { it.address == terminal.address }
                } }
            } ?: structure.spaces.find { it.id == saved.spaceId }
            space?.let {
                browser.openSpace(it.id)
                val tab = it.tabs.find { tab -> terminal != null && tab.panes.any { pane ->
                    pane.terminalAddress == terminal.address || pane.subAgents.any { it.address == terminal.address }
                } } ?: it.tabs.find { it.id == saved.tabId }
                tab?.let(browser::selectTab)
            }
            if (terminal != null) {
                val origin = Screen.Browse(browser)
                if ((saved.view == AgentView.CHAT || restoreTerminalParent != null) && terminal.agentStatus != null) {
                    chat(link, terminal, origin)
                    if (saved.view == AgentView.TERMINAL) {
                        if (restoreTerminalParent == "chat") openTerminal(terminal) else switchView(AgentView.TERMINAL)
                    }
                } else observe(link, terminal, origin)
            }
        }
        fun updateStructure(structure: HostStructure) {
            browser.update(structure)
            val id = pendingLaunch
            pendingLaunch = null
            if (id != null && (screen as? Screen.Browse)?.browser === browser) {
                val saved = browser.savedLaunches.find { it.id == id }
                if (saved == null) savedLaunchMissing()
                else if (confirmSavedLaunch) prompt = Prompt.ConfirmSavedLaunch(browser, saved)
                else runSaved(browser, saved)
            }
            restoreIfReady()
            pendingQuickOpen?.takeIf { it.host.id == browser.host.id }?.let {
                if ((screen as? Screen.Browse)?.browser === browser) revealQuickOpen(browser, it)
            }
        }
        val sharedBoard = agentHome?.board?.takeIf { it.links[link.host.id] === link }
        if (sharedBoard != null) {
            // The board keeps the only SSH line and subscriptions; Browse is a view of that feed.
            snapshotFlow {
                sharedBoard.quickOpenSources.firstOrNull { it.host.id == link.host.id } to sharedBoard.watchErrors[link.host.id]
            }.collect { (source, error) ->
                browser.watchError = error
                if (source == null) {
                    if (error == null) browser.startingWatch()
                } else {
                    source.structure?.let(::updateStructure)
                    browser.updateTerminals(source.terminals)
                    terminals = source.terminals
                    restoreIfReady()
                }
            }
            return@launch
        }
        try {
            link.keepWatching(onFailure = { browser.watchError = it }, onStart = browser::startingWatch) { conn, _ ->
                val structure = conn.watchStructure(object : StructureListener {
                    override fun onStructure(structure: HostStructure) {
                        scope.launch { updateStructure(structure) }
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
                            terminals = list
                            restoreIfReady()
                            updateConversations(browser.host.id, list)
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
        userNavigation()
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

    /** A confirmed fallback: never infer a picker index or send its acceptance key. */
    internal fun rewindChat(entryId: String) {
        val chat = (screen as? Screen.Chat)?.chat ?: return
        if (!chat.canRewind || chat.rewindTarget(entryId) == null) return
        openTerminal(chat.terminal)
        val viewer = (screen as? Screen.Terminal)?.viewer ?: return
        viewer.rewindPicker = true
        viewer.press(io.github.shiroel.aries.core.TerminalKey.Escape)
        viewer.press(io.github.shiroel.aries.core.TerminalKey.Escape)
    }

    /** Shows [terminal] over [returnTo]: an Agent in the saved [agentView], any other Terminal as a Terminal. */
    private fun show(link: HostLink, terminal: TerminalInfo, returnTo: Screen) {
        if (terminal.agentStatus != null && agentView == AgentView.CHAT) {
            chat(link, terminal, returnTo)
        } else {
            observe(link, terminal, returnTo)
        }
    }

    /** One owner per full Host/Terminal identity, independent of navigation and SSH lines. */
    private val conversations = mutableStateMapOf<AgentKey, ChatModel>()

    private fun conversation(link: HostLink, terminal: TerminalInfo): ChatModel =
        conversations.getOrPut(AgentKey(link.host.id, terminal.address)) {
            ChatModel(core, link.host, terminal, link, keyWork)
        }.also { it.bind(link, terminal) }

    /** Needs sheets reuse the same answer/outbox owner as Chat, without changing navigation. */
    internal fun taskQuestion(board: AgentBoard, key: AgentKey): ChatModel? {
        val entry = board.find(key)?.takeIf { it.live } ?: return null
        val link = board.links[key.hostId]?.takeIf { it.state == LinkState.Up && it.connection != null } ?: return null
        return conversation(link, entry.terminal)
    }

    /** A submitted sheet answer outlives the sheet, just like a submitted Chat message. */
    internal fun startTaskQuestion(chat: ChatModel) { chat.start(scope, paused = screensPaused) }

    internal fun isViewedChat(chat: ChatModel) = liveChat === chat

    /** Read already available transcript facts; the task list never opens per-row SSH watches. */
    internal fun taskTranscript(key: AgentKey) = conversations[key]?.snapshot

    internal fun taskPreview(entry: AgentEntry) = if (entry.live)
        conversations[entry.key]?.currentTranscript(entry.terminal) else null

    private val taskChanges = mutableMapOf<AgentKey, TasksChangesState>()
    internal fun taskChanges(key: AgentKey) = taskChanges.getOrPut(key) { TasksChangesState() }
    internal fun taskChangeStats(key: AgentKey, terminal: TerminalInfo) = taskChanges[key]?.takeIf {
        terminal.agentStatus != AgentStatus.Working && it.matches(terminal, agentLinks[key.hostId]?.connection)
    }?.stats

    private fun chat(link: HostLink?, terminal: TerminalInfo, returnTo: Screen, draft: String = "",
        preserveReadingPosition: Boolean = false) {
        val line = link ?: return
        restoreSettingsBase()
        viewing?.cancel()
        viewing = null
        liveChat?.stop()
        val chat = conversation(line, terminal)
        // Drafts/answers belong to the cached conversation; the viewport belongs to this visit.
        if (!preserveReadingPosition) chat.readingSession = ChatReadingSession()
        if (draft.isNotEmpty()) chat.appendDraft(draft)
        screen = Screen.Chat(chat, if (returnTo == Screen.Settings) screen else returnTo)
        chat.start(scope, paused = screensPaused)
    }

    /**
     * The Terminal/Chat toggle: shows the open Agent as [view] in place, so
     * back still returns to where it was opened, and saves [view] for the
     * next Agent opened.
     */
    fun switchView(view: AgentView) {
        userNavigation()
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
                    chat(link, s.viewer.terminal, s.returnTo, preserveReadingPosition = s.fromChatToggle)
                }
            }
            is Screen.Chat -> {
                if (view != AgentView.TERMINAL) return
                val link = s.chat.link ?: return
                s.chat.stop()
                observe(link, s.chat.terminal, s.returnTo, fromChatToggle = true)
            }
            else -> return
        }
        guard {
            core.setAgentView(view)
            agentView = core.agentView()
        }
    }

    /** An Agent Terminal's preview uses that same Agent's Chat and existing attachment path. */
    fun attachPreviewToChat(context: android.content.Context, content: SharedContent, cleanup: () -> Unit): Boolean {
        val s = screen as? Screen.Terminal ?: run { cleanup(); return false }
        if (s.viewer.terminal.agentStatus == null || s.viewer.terminal.state != "live") {
            cleanup()
            return false
        }
        val existing = (s.returnTo as? Screen.Chat)?.chat
        if (existing != null && (!existing.canEdit || existing.uploading)) {
            cleanup()
            return false
        }
        switchView(AgentView.CHAT)
        val chat = (screen as? Screen.Chat)?.chat ?: run { cleanup(); return false }
        return chat.attach(context.applicationContext, content, cleanup = cleanup)
    }

    /** Adds output to an editable draft, never to the Terminal input stream. */
    fun sendTerminalSelectionToDraft(text: String) {
        val s = screen as? Screen.Terminal ?: return
        if (text.isEmpty()) return
        // Output may itself contain Markdown fences. Keep all of it inside one code block.
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        val block = "$fence\n$text${if (text.endsWith('\n')) "" else "\n"}$fence"
        if (s.viewer.terminal.agentStatus != null) {
            switchView(AgentView.CHAT)
            (screen as? Screen.Chat)?.chat?.let { chat ->
                chat.appendDraft(block)
                return
            }
        }
        s.viewer.draft = if (s.viewer.draft.isEmpty()) block else s.viewer.draft + "\n\n" + block
        s.viewer.openComposer()
    }

    /** Re-attaches with `resumed` on each new line, so the screen comes back after a network change. */
    private fun observe(link: HostLink, terminal: TerminalInfo, returnTo: Screen, fromChatToggle: Boolean = false) {
        viewing?.cancel()
        if (returnTo !is Screen.Chat) liveChat?.stop()
        val owner = conversation(link, terminal)
        val viewer = TerminalViewer(terminal, owner.terminalComposer)
        viewer.inputHistory = owner.inputHistory
        viewer.link = link
        if (attachMode == AttachMode.INPUT) viewer.startTyping()
        screen = Screen.Terminal(viewer, returnTo, fromChatToggle)
        viewing = scope.launch {
            try {
                link.keepWatching(onFailure = { viewer.watchError = it }, onStart = {
                    // Retry invalidates a failed attachment's input. A new line may
                    // already own keys queued while the old search finishes.
                    if (viewer.watchError != null) viewer.discardInput()
                    viewer.watchError = null
                }) { conn, resumed ->
                    if (!viewer.typing) {
                        // Watch until the first key/paste, then let go of the observer.
                        val options = AttachOptions(observe = true, cols = 80u, rows = 24u, history = SCROLLBACK_LINES, resumed = resumed)
                        val exited = coroutineScope {
                            val watching = async { draw(conn, viewer, options, null) }
                            val focused = async { snapshotFlow { viewer.typing }.first { it } }
                            val done = select { watching.onAwait { it }; focused.onAwait { false } }
                            focused.cancel()
                            watching.cancelAndJoin()
                            done
                        }
                        if (exited) return@keepWatching
                    }
                    // An ordinary viewer at the phone's grid; from now on the phone sets the size.
                    // The Canvas publishes only settled grids, including before the first attach.
                    val (cols, rows) = snapshotFlow { viewer.phoneGrid }.filterNotNull().first()
                    val options = AttachOptions(
                        observe = false, cols = cols.toUShort(), rows = rows.toUShort(), history = SCROLLBACK_LINES, resumed = resumed,
                    )
                    draw(conn, viewer, options) { view ->
                        // A failed write propagates to the link; it must not silently stop the input job.
                        launch {
                            try {
                                snapshotFlow { viewer.phoneGrid }.filterNotNull().distinctUntilChanged()
                                    .dropWhile { it == cols to rows }
                                    .collect { (c, r) -> view.resize(c.toUShort(), r.toUShort()) }
                            } catch (e: CoreException) {
                            }
                        }
                        launch {
                            // Preserve order only on the original line. A replacement connection
                            // must never receive commands left behind by a dropped write.
                            for (pending in viewer.inputs) {
                                val input = pending.input
                                if (!viewer.canInput || pending.connection !== conn || link.connection !== conn) {
                                    if (input is TerminalInput.Paste) viewer.pasteFinished(input, delivered = false)
                                    continue
                                }
                                when (input) {
                                    is TerminalInput.Text -> view.sendText(input.text)
                                    is TerminalInput.Paste -> {
                                        var delivered = false
                                        try {
                                            view.pasteText(input.text)
                                            delivered = true
                                        } finally {
                                            viewer.pasteFinished(input, delivered)
                                        }
                                        viewer.inputHistory?.remember(input.text)
                                    }
                                    is TerminalInput.Key -> view.sendKey(input.key, input.mods)
                                }
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
        val view = try {
            conn.attachTerminal(viewer.terminal.address, options)
        } catch (e: Exception) {
            // Failure can precede the view and its finally block. Only the
            // intentional observer → typing cancellation preserves the first key.
            if (e is CoreException || !options.observe || viewer.link?.connection !== conn) viewer.discardInput(conn)
            throw e
        }
        val localChanges = Channel<Unit>(Channel.CONFLATED)
        val localOperations = Channel<TerminalLocalOperation>(Channel.UNLIMITED,
            onUndeliveredElement = { it.result.cancel() })
        suspend fun localOperation(apply: (TerminalView) -> Unit) {
            val request = TerminalLocalOperation(apply)
            // A debounced UI effect can still hold the previous attach's callback.
            if (!localOperations.trySend(request).isSuccess) return
            try {
                request.result.await()
            } finally {
                request.result.cancel()
            }
        }
        viewer.scroller = { lines -> view.scrollTo(lines).also { localChanges.trySend(Unit) } }
        viewer.searcher = { query, caseSensitive, regex ->
            localOperation { it.search(query, caseSensitive, regex) }
        }
        viewer.searchNavigator = { backwards ->
            localOperation { it.searchNext(backwards) }
        }
        try {
            return coroutineScope {
                launch {
                    for (request in localOperations) {
                        if (!request.result.isActive) continue
                        try {
                            withContext(Dispatchers.Default) { request.apply(view) }
                            localChanges.trySend(Unit)
                            request.result.complete(Unit)
                        } catch (e: CancellationException) {
                            request.result.cancel()
                            throw e
                        } catch (e: CoreException) {
                            request.result.completeExceptionally(e)
                        }
                    }
                }
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
                    // Keep the finished VT readable/searchable until the user leaves.
                    // After Exited, next() yields a screen only for local changes.
                    side?.cancel()
                    for (change in localChanges) {
                        try {
                            val update = view.next()
                            if (update is TerminalUpdate.Screen) viewer.apply(update.frame)
                        } catch (_: CoreException.Closed) {
                            // A buffered signal may already have been drawn before Exited.
                        }
                    }
                    true
                } finally {
                    side?.cancel()
                }
            }
        } finally {
            if (!options.observe || viewer.link?.connection !== conn) viewer.discardInput(conn)
            viewer.scroller = null
            viewer.searcher = null
            viewer.searchNavigator = null
            localOperations.cancel()
            localChanges.close()
            withContext(NonCancellable) { withTimeoutOrNull(2_000L) { view.detach() } }
            view.close()
        }
    }

    fun setDefaultAttachMode(mode: AttachMode) = guard {
        core.setAttachMode(mode)
        attachMode = core.attachMode()
    }

    /** Leaves the open Terminal (it keeps running) and shows where it was opened from. */
    fun closeTerminal() {
        userNavigation()
        val s = screen as? Screen.Terminal ?: return
        viewing?.cancel()
        viewing = null
        if (s.viewer.link === callLink && s.returnTo !is Screen.Chat) {
            ownCallLink(null)
        }
        screen = s.returnTo
    }

    /** The home board survives Host management and Browse; replacing its scope releases it. */
    private var agentHome: Screen.Agents? = null

    /** Lines and watch jobs of [Screen.Agents], per Host id. */
    private val agentLinks = mutableMapOf<String, HostLink>()
    private val agentConns: Map<String, HostConnection>
        get() = agentLinks.mapNotNull { (id, link) -> link.connection?.let { id to it } }.toMap()
    private val agentJobs = mutableListOf<Job>()

    private data class ShareRequest(val intent: android.content.Intent, val content: SharedContent)

    /** Keep the original URI grant envelope until an Agent takes ownership (or the user cancels). */
    private var shareRequest by mutableStateOf<ShareRequest?>(null)
    val pendingShare: SharedContent? get() = shareRequest?.content
    val pendingShareIntent: android.content.Intent? get() = shareRequest?.intent?.let { android.content.Intent(it) }

    /** Both cold and warm ACTION_SEND routes use the existing multi-Host Agent list. */
    fun receiveShare(intent: android.content.Intent): Boolean {
        val content = SharedContent.from(intent) ?: return false
        openAgents()
        shareRequest = ShareRequest(android.content.Intent(intent), content)
        return true
    }

    fun cancelShare() { shareRequest = null }

    /** Warm navigation returns to the retained home without applying the startup location again. */
    fun openThreadHome() = openAgents()

    /** Only cold startup restores the last Agent, validated against the first fresh list. */
    private fun restoreThreadHome() {
        val saved = locationStore?.loadAgent()
        back()
        openAgents(focus = saved?.let { location ->
            hosts.find { it.id == location.hostId }?.let { host ->
                location.terminal?.let { AgentKey(host.id, it) }
            }
        }, openChat = true, restoreAgent = true)
    }

    internal fun threadTitle(entry: AgentEntry): String =
        conversations[entry.key]?.let(::threadTitle) ?: displayName(entry.terminal)

    internal fun threadTitle(chat: ChatModel): String = chat.snapshot?.entries
        ?.firstNotNullOfOrNull { (it.body as? ChatEntryBody.User)?.text?.trim()?.takeIf(String::isNotEmpty) }
        ?.lineSequence()?.firstOrNull()?.take(160) ?: displayName(chat.terminal)

    internal fun beginThreadTask(host: Host, showDialog: Boolean = true, resetFolder: Boolean = false) {
        val link = links.firstOrNull { it.host.id == host.id && it.state == LinkState.Up && it.connection != null }
        if (resetFolder) task?.let { threadTaskSelection = ThreadTaskSelection(host.id, it.input, showDialog) }
        if (link == null) {
            // Connecting leaves the inline home for Browse. Resume in its task dialog there.
            threadTaskSelection = threadTaskSelection?.copy(showDialog = true)
            taskVisible = false
            newTask(host)
        } else prepareTask(link, showDialog = showDialog)
    }

    /** Connects to every Host and shows all their Agents in one list. */
    fun openAgents() {
        val retained = agentHome?.takeIf { it.board.hosts == hosts }
        if (retained != null) {
            showHosts()
            screen = retained
        } else {
            back()
            suppressLocationSave = false
            openAgents(focus = null)
        }
    }

    private fun openNotificationTarget(target: PushTarget) {
        if (target.agents) {
            explicitSurfaceEntry = true
            back()
            openAgents(focus = null, selectedHosts = hosts.filter { it.id == target.hostId })
        } else openPushedAgent(target.hostId, target.terminal, openChat = target.chat)
    }

    /** Resolve QS taps from the latest persisted pull, including the whole machine address. */
    fun openTile() {
        explicitSurfaceEntry = true
        scope.launch {
            val target = withContext(keyWork) {
                try { io.github.shiroel.aries.AgentSurfaces.attention(core) }
                catch (_: CoreException) { null }
            }
            if (target == null) openAgents() else openPushedAgent(target.hostId, target.terminal)
        }
    }

    /** Every exported Activity entry uses this gate; only published capabilities bypass approval. */
    fun openSavedLaunch(context: android.content.Context, intent: android.content.Intent) {
        val target = io.github.shiroel.aries.SavedLaunchTarget.from(intent) ?: return
        openSavedLaunch(target.hostId, target.launchId, !io.github.shiroel.aries.SavedLaunchAuthorization.trusted(context, intent))
    }

    private fun openSavedLaunch(hostId: String, launchId: String, confirm: Boolean) {
        explicitSurfaceEntry = true
        back()
        prompt = null
        val host = hosts.find { it.id == hostId }
        if (host == null) savedLaunchMissing() else connect(host, restore = null, savedLaunchId = launchId, confirmSavedLaunch = confirm)
    }

    fun confirmSavedLaunch(approval: Prompt.ConfirmSavedLaunch) {
        if (prompt !== approval) return
        prompt = null
        if ((screen as? Screen.Browse)?.browser !== approval.browser) return
        runSaved(approval.browser, approval.saved)
    }

    private fun savedLaunchMissing() {
        prompt = Prompt.Problem(Message(R.string.shortcuts_missing_title), Message(R.string.shortcuts_missing_body))
    }

    /** Notifications and drawer entries reuse a live owner; cold entry opens only its Host's list. */
    fun openPushedAgent(hostId: String, address: String, openChat: Boolean = false) {
        val host = hosts.find { it.id == hostId }
        // Host management can cover a warm home; keep that board as the detail's owner.
        if (host != null && (screen == Screen.Hosts || screen is Screen.Connecting)) {
            agentHome?.let { retained ->
                showHosts()
                screen = retained
            }
        }
        if (host != null && screen != Screen.Hosts && screen !is Screen.Connecting) {
            openAgentTarget(host, address, openChat)
            return
        }
        back()
        openAgents(focus = null, selectedHosts = listOfNotNull(host))
        if (host != null) openAgentTarget(host, address, openChat)
        if (host == null) {
            prompt = Prompt.Problem(Message(R.string.push_terminal_gone_title), Message(R.string.push_host_gone_body, listOf(address)))
        }
    }

    /** [focus]: the Agent whose Terminal to open once its Host's list arrives. */
    private fun openAgents(focus: AgentKey?, openChat: Boolean = false, selectedHosts: List<Host> = hosts,
        restoreAgent: Boolean = false, focusView: AgentView? = null, focusTerminalParent: String? = null) {
        val board = AgentBoard(selectedHosts)
        lastKnownStates.forEach(board::seed)
        val agents = Screen.Agents(board)
        agentHome = agents
        val focusVersion = navigationVersion
        screen = agents
        selectedHosts.forEach { host ->
            var focusing = focus?.takeIf { it.hostId == host.id }
            val link = link(host, null)
            agentLinks[host.id] = link
            board.links[host.id] = link
            agentJobs += scope.launch {
                try {
                    // A new line starts from its snapshot, so what changed while away shows.
                    link.keepWatching(onFailure = { board.fail(host.id, it) }, onStart = { board.starting(host.id) }) { c, _ ->
                        board.savedLaunches[host.id] = c.savedLaunches()
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
                                    updateConversations(host.id, list)
                                    terminalOn(host.id)?.update(list)
                                    focusing?.let { key ->
                                        focusing = null
                                        if (screen === agents && navigationVersion == focusVersion) {
                                            if (restoreAgent) {
                                                list.find { it.address == key.address && it.state == "live" && it.agentStatus != null }?.let {
                                                    openAgent(board, AgentEntry(host, it), openChat = true)
                                                }
                                            } else if (focusView != null) {
                                                val terminal = list.find { it.address == key.address && it.state == "live" }
                                                if (terminal != null) {
                                                    if (terminal.agentStatus != null && (focusView == AgentView.CHAT || focusTerminalParent != null)) {
                                                        chat(link, terminal, agents)
                                                        if (focusView == AgentView.TERMINAL) {
                                                            if (focusTerminalParent == "chat") openTerminal(terminal) else switchView(AgentView.TERMINAL)
                                                        }
                                                    } else observe(link, terminal, agents)
                                                }
                                            } else focusOn(agents, host, key, list, openChat)
                                        }
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

    private fun focusOn(agents: Screen.Agents, host: Host, key: AgentKey, list: List<TerminalInfo>, openChat: Boolean) {
        val terminal = list.find { it.address == key.address && it.state == "live" }
        if (terminal == null) {
            prompt = Prompt.Problem(
                Message(R.string.push_terminal_gone_title),
                Message(R.string.push_terminal_gone_body, listOf(key.address, host.name)),
            )
            return
        }
        openAgent(agents.board, AgentEntry(host, terminal), openChat = openChat)
    }

    /** Opens an Agent in its saved view (or Chat for a question); the visible screen owns seen sync. */
    fun openAgent(board: AgentBoard, entry: AgentEntry, openChat: Boolean = false) {
        userNavigation()
        val agents = screen as? Screen.Agents ?: return
        if (agents.board !== board) return
        val current = board.find(entry.key) ?: return
        if (!current.live) {
            openAgentTarget(current.host, current.terminal.address, openChat)
            return
        }
        val link = agentLinks[current.host.id] ?: return
        val t = current.terminal
        val shared = pendingShare
        if (shared != null) {
            if (t.agentStatus == null || t.state != "live") return
            chat(link, t, agents)
            (screen as Screen.Chat).chat.pendingShare = shared
            cancelShare()
        } else if (openChat) chat(link, t, agents) else show(link, t, agents)
    }

    /** Pin from any shared row, using the connection already owned by that surface. */
    fun canPin(entry: AgentEntry): Boolean = pinLink(entry)?.let { it.state == LinkState.Up && it.connection != null } == true

    private fun pinLink(entry: AgentEntry): HostLink? = upLink(entry.host.id)

    fun pin(entry: AgentEntry, pinned: Boolean) {
        if (!canPin(entry)) return
        val link = pinLink(entry) ?: return
        val conn = link.connection ?: return
        scope.launch {
            try {
                val terminal = conn.setPinned(entry.terminal.address, pinned)
                fun replace(route: Screen) {
                    when (route) {
                        is Screen.Agents -> route.board.replace(entry.host.id, terminal)
                        is Screen.Browse -> if (route.browser.host.id == entry.host.id) route.browser.replaceTerminal(terminal)
                        is Screen.Chat -> replace(route.returnTo)
                        is Screen.Terminal -> replace(route.returnTo)
                        else -> Unit
                    }
                }
                replace(screen)
            } catch (e: CoreException) { prompt = problemFor(e) }
        }
    }

    /** Pins or unpins an Agent for every client. */
    fun pin(board: AgentBoard, entry: AgentEntry, pinned: Boolean) =
        labelWrite(board, entry) { it.setPinned(entry.terminal.address, pinned) }

    private fun labelWrite(board: AgentBoard, entry: AgentEntry, write: suspend (HostConnection) -> TerminalInfo) {
        val conn = upLink(entry.host.id)?.connection ?: return
        scope.launch {
            try {
                board.replace(entry.host.id, write(conn))
            } catch (e: CoreException) {
                prompt = problemFor(e)
            }
        }
    }

    private fun closeAgents() {
        agentHome = null
        agentJobs.forEach { it.cancel() }
        agentJobs.clear()
        agentLinks.values.forEach { it.close() }
        agentLinks.clear()
    }

    /** The app is in front: follow eligible Calls until [pauseCalls]. */
    private var callsActive = false

    var callInboxOpen by mutableStateOf(false)
        private set

    internal var callInboxOpenedAtNavigation = 0L
        private set

    fun openCallInbox() {
        navigationIntent()
        callInboxOpenedAtNavigation = overlayNavigationVersion
        callInboxOpen = true
        calls.dismissBanner()
        calls.watch(hosts, everyHost = true)
    }

    fun callFeedSettings(host: Host) {
        when (calls.startFailures[host.id]) {
            is CoreException.KeyInvalidated -> openKeyRecovery()
            is CoreException.NoKey, is CoreException.KeyLocked, is CoreException.BiometricUnavailable -> openKeySettings()
            else -> openHostSettings(host)
        }
    }

    private var taskNeedsActive = false

    private fun reconcileCallWatches() {
        if (callsActive) calls.watch(hosts,
            everyHost = callInboxOpen || taskNeedsActive,
            retainedHostIds = setOfNotNull(currentAgentKey?.hostId))
        else calls.stopAll()
    }

    internal fun beginTaskNeeds() { taskNeedsActive = true; reconcileCallWatches() }

    internal fun endTaskNeeds() {
        taskNeedsActive = false
        reconcileCallWatches()
    }

    fun closeCallInbox() {
        if (!callInboxOpen) return
        callInboxOpen = false
        if (agentOpeningFromInbox) cancelAgentOpen()
        openingCall = null
        reconcileCallWatches()
    }

    fun resumeCalls() {
        callsActive = true
        observeLastKnownStates()
        reconcileCallWatches()
    }

    fun pauseCalls() {
        callsActive = false
        lastKnownWatch?.cancel()
        lastKnownWatch = null
        calls.stopAll()
    }

    private fun hostsChanged() {
        // A removed Host must not keep its retained home line alive.
        if (agentHome?.board?.hosts?.any { retained -> hosts.none { it.id == retained.id } } == true) closeAgents()
        onSavedLaunchesChanged()
        calls.retainHosts(hosts)
        reconcileCallWatches()
    }

    /** Live lists replace stored lists per Host, including an empty live list. */
    val drawerAgents: List<AgentEntry>
        get() = orderAgents(quickOpenSources.flatMapIndexed { index, source ->
            withAgentSpaces(agentFamily(source.host, source.terminals.filter { it.state == "live" }), source.structure)
                .map { it.copy(hostOrder = index, live = source.live, lastUpdatedMs = source.fetchedAtMs?.toLong()) }
        })

    val currentAgentKey: AgentKey?
        get() = when (val route = screen) {
            is Screen.Chat -> AgentKey(route.chat.host.id, route.chat.terminal.address)
            is Screen.Terminal -> route.viewer.link?.host?.id?.let { AgentKey(it, route.viewer.terminal.address) }
            else -> null
        }

    val nextWaitingAgent: AgentEntry?
        get() = currentAgentKey?.let { current -> drawerAgents.firstOrNull {
            it.key != current && quickOpenSources.any { source -> source.host.id == it.host.id && source.live } &&
                isWaiting(it.terminal.agentStatus)
        } }

    /** A sheet owns this watch and disposes it when leaving. Reuses the normal draft/answer owner.
     * Only existing live connections may answer in place; other Hosts use the normal unlock route.
     */
    internal fun previewWaitingChat(entry: AgentEntry): ChatModel? {
        val line = links.firstOrNull { it.host.id == entry.host.id && it.state == LinkState.Up } ?: return null
        return conversation(line, entry.terminal)
    }

    /** Retire the sheet's watch before navigation starts this same conversation owner again. */
    internal fun releaseWaitingChat(key: AgentKey) {
        conversations[key]?.takeIf { it !== liveChat }?.stop()
    }

    /** Switch in place over the original list, using the same entry path as drawer and inbox. */
    fun openNextWaitingAgent() {
        val next = nextWaitingAgent ?: return
        openAgentTarget(next.host, next.terminal.address)
    }

    /** A home badge opens the first waiting Agent, or the list if the Host has none. */
    fun openHostWaiting(host: Host) {
        val next = drawerAgents.firstOrNull { it.host.id == host.id && isWaiting(it.terminal.agentStatus) }
        if (next == null) {
            back()
            openAgents(focus = null, selectedHosts = listOf(host))
        } else openPushedAgent(next.host.id, next.terminal.address)
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
        val terminal = find(screen) ?: drawerAgents.firstOrNull { it.key == key }?.terminal
            ?: quickOpenSources.firstOrNull { it.host.id == key.hostId }?.terminals?.firstOrNull { it.address == address }
        return terminal?.let(::displayName)?.takeIf { it.isNotBlank() }
    }

    fun openHost(host: Host) {
        connect(host)
    }

    /** Uses whole Host/address identity and the existing saved-view/notification route. */
    fun openDrawerAgent(entry: AgentEntry) {
        val board = (screen as? Screen.Agents)?.board
        if (board?.find(entry.key) != null && agentLinks[entry.host.id]?.state == LinkState.Up) {
            openAgent(board, entry)
        } else {
            openPushedAgent(entry.host.id, entry.terminal.address)
        }
    }

    /** Activity state is deliberately limited to IDs and destination flags. */
    fun saveSettingsState(): android.os.Bundle? {
        pendingSettingsState?.let { return it.bundle() }
        if (screen != Screen.Settings && screen !is Screen.JumpHosts) return null
        fun agentBase(route: Screen): Screen.Agents? = when (route) {
            is Screen.Agents -> route
            is Screen.Chat -> agentBase(route.returnTo)
            is Screen.Terminal -> agentBase(route.returnTo)
            else -> null
        }
        val origin = settingsColdReturn ?: SettingsReturnState(
            lastLocation() ?: LastLocation(hostId = (ownedScreen as? Screen.Connecting)?.host?.id), agentBase(ownedScreen)?.board?.hosts?.map { it.id },
            (ownedScreen as? Screen.Terminal)?.let {
                if (it.returnTo is Screen.Chat) "chat" else if (it.fromChatToggle) "toggle" else null
            },
        )
        return SettingsNavigationState(when {
            screen is Screen.JumpHosts -> "jumps"
            settingsHost != null -> "host"
            settingsKeys -> if (recoveringKeys) "recovery" else "keys"
            settingsDiagnostics -> "diagnostics"
            else -> "settings"
        }, (screen as? Screen.JumpHosts)?.host?.id ?: settingsHost?.id, origin).bundle()
    }

    /** Queue until start(), behind App Lock and the resumed biometric authorizer. */
    fun restoreSettingsState(state: android.os.Bundle?) {
        if (!started) pendingSettingsState = SettingsNavigationState.read(state)
    }

    private fun applySettingsState(saved: SettingsNavigationState) {
        settingsColdReturn = saved.origin
        screen = Screen.Settings
        settingsKeys = saved.page == "keys" || saved.page == "recovery"
        recoveringKeys = saved.page == "recovery"
        settingsDiagnostics = saved.page == "diagnostics"
        settingsHost = hosts.find { it.id == saved.hostId }
        settingsHost?.let { host ->
            loadHostSettings(host)
            if (saved.page == "jumps") editJumps(host)
        }
    }

    private fun restoreSettingsReturn(saved: SettingsReturnState) {
        val location = saved.location
        if (saved.agentHosts != null) {
            back()
            openAgents(focus = location.hostId?.let { host -> location.terminal?.let { AgentKey(host, it) } },
                selectedHosts = hosts.filter { it.id in saved.agentHosts },
                focusView = location.view, focusTerminalParent = saved.terminalParent)
        } else {
            val host = hosts.find { it.id == location.hostId }
            if (host == null) back() else {
                startupHostId = host.id
                connect(host, location, restoreTerminalParent = saved.terminalParent)
            }
        }
    }

    /** Keep the route and its live owners while changing app or Host settings. */
    fun openSettings() {
        userNavigation()
        if (screen != Screen.Settings && screen !is Screen.JumpHosts) settingsReturnTo = screen
        closeQuickOpen()
        pendingQuickOpen = null
        closeCallInbox()
        settingsHost = null
        settingsKeys = false
        recoveringKeys = false
        settingsDiagnostics = false
        screen = Screen.Settings
    }

    var settingsReason by mutableStateOf<Message?>(null)
        private set

    /** Shared destination for Host menus, Browse guidance and inbox install links (ticket 51). */
    fun openHostSettings(host: Host, reason: Message? = null) {
        openSettings()
        settingsReason = reason
        settingsHost = host
        loadHostSettings(host)
    }

    fun openKeySettings() {
        openSettings()
        settingsKeys = true
    }

    fun openDiagnostics() {
        openSettings()
        settingsDiagnostics = true
    }

    fun settingsBack() {
        if (screen is Screen.JumpHosts) {
            screen = Screen.Settings
        } else if (settingsHost != null || settingsKeys || settingsDiagnostics) {
            settingsHost = null
            settingsKeys = false
            settingsDiagnostics = false
        } else {
            val origin = settingsReturnTo
            settingsReturnTo = null
            val cold = settingsColdReturn
            settingsColdReturn = null
            if (origin != null) screen = origin
            else if (cold != null) restoreSettingsReturn(cold)
            else back()
        }
    }

    /** A layout change remaps only the previous layout's home, retaining any open detail. */
    internal fun homeLayoutChanged(previous: AppLayout, next: AppLayout) {
        if (previous == next) return
        val origin = settingsReturnTo ?: screen
        val wasHome = if (previous == AppLayout.CONSOLE) origin == Screen.Hosts else origin is Screen.Agents
        if (!wasHome) return
        val settings = screen.takeIf { it == Screen.Settings || it is Screen.JumpHosts }
        if (next == AppLayout.CONSOLE) {
            if (settings != null) settingsReturnTo = Screen.Hosts else showHosts()
        } else {
            openAgents()
            if (settings != null) {
                settingsReturnTo = screen
                screen = settings
            }
        }
    }

    fun setDefaultAgentView(view: AgentView) = guard {
        core.setAgentView(view)
        agentView = core.agentView()
    }

    /** A detail owns this line when no Browse or Agent list owns its Host. */
    private var callLink: HostLink? = null
    private var callStatus: Job? = null
    var openingCall by mutableStateOf<String?>(null)
        private set

    /** A detail-only line supplies status for its own Host, including after reconnect. */
    private fun ownCallLink(link: HostLink?) {
        callStatus?.cancel()
        callStatus = null
        callLink?.close()
        callLink = link
        if (link == null) return
        callStatus = scope.launch {
            link.keepWatching(onFailure = { error ->
                if (callLink === link) prompt = problemFor(error)
            }) { conn, _ ->
                val watch = conn.watchTerminals()
                try {
                    while (true) {
                        val list = watch.next()
                        updateConversations(link.host.id, list)
                        terminalOn(link.host.id)?.update(list)
                    }
                } finally { watch.stop() }
            }
        }
    }

    private var agentOpeningLink: HostLink? = null
    private var agentOpeningJob: Job? = null
    private var agentOpenRequest = 0
    private var agentOpeningFromInbox = false
    var agentOpening by mutableStateOf<Message?>(null)
        private set
    private var agentOpeningHostId: String? = null

    private var agentOpenPrompt: Prompt? = null

    private fun setAgentOpenPrompt(value: Prompt) {
        agentOpenPrompt = value
        prompt = value
    }

    fun cancelAgentOpen() {
        if (prompt === agentOpenPrompt) prompt = null
        agentOpenPrompt = null
        agentOpenRequest++
        agentOpeningJob?.cancel()
        agentOpeningJob = null
        agentOpeningLink?.close()
        agentOpeningLink = null
        agentOpeningFromInbox = false
        agentOpening = null
        agentOpeningHostId = null
        openingCall = null
    }

    private fun restoreSettingsBase() {
        if (settingsColdReturn != null) screen = Screen.Hosts
        settingsColdReturn = null
        settingsReturnTo?.let { screen = it }
        settingsReturnTo = null
    }

    /** Resolve against exactly one Host. Pending trust retries this same action on this same link. */
    private fun openAgentTarget(host: Host, address: String, openChat: Boolean = false, callKey: String? = null) {
        val fromInbox = callKey != null
        if (fromInbox) navigationIntent() else userNavigation()
        restoreSettingsBase()
        openingCall = callKey
        val version = navigationVersion
        val request = agentOpenRequest
        fun current() = navigationVersion == version && request == agentOpenRequest && (!fromInbox || callInboxOpen)
        val existing = preferredLink(host.id)
        val target = existing ?: link(host, null)
        val board = agentHome?.board?.takeIf { it.links[host.id] === target }
        val name = board?.find(AgentKey(host.id, address))?.terminal?.let(::displayName)
            ?: drawerAgents.firstOrNull { it.key == AgentKey(host.id, address) }?.terminal?.let(::displayName) ?: address
        agentOpening = if (target.state == LinkState.Up) null else Message(R.string.agent_opening, listOf(name))
        agentOpeningHostId = host.id
        agentOpeningFromInbox = fromInbox
        if (existing == null) agentOpeningLink = target
        agentOpeningJob = scope.launch {
            var adopted = false
            try {
                val terminal = coroutineScope {
                    // Trust is an explicit decision, so its time does not consume the connection budget.
                    val deadline = launch {
                        var remaining = 20_000L
                        kotlinx.coroutines.flow.combine(screensActive,
                            snapshotFlow { (target.state as? LinkState.Down)?.error }) { active, error -> active to error }
                            .collectLatest { (active, error) ->
                                if (!active) return@collectLatest
                                when (error) {
                                    is CoreException.HostKeyUnknown -> setAgentOpenPrompt(Prompt.TrustHostKey(host, error.fingerprint))
                                    is CoreException.JumpHostKeyUnknown -> setAgentOpenPrompt(Prompt.TrustJumpHostKey(host, error.hop, error.address, error.fingerprint))
                                    null -> {
                                        val began = elapsedRealtime()
                                        try {
                                            delay(remaining.coerceAtLeast(0))
                                            throw CoreException.Unreachable(host.name)
                                        } finally {
                                            remaining -= (elapsedRealtime() - began).coerceAtLeast(0)
                                        }
                                    }
                                    else -> throw error
                                }
                            }
                    }
                    try {
                        if (target.state !is LinkState.Down) target.retryNow()
                        target.keep { conn, _ ->
                            if (board == null) conn.terminals() else {
                                // Reuse the board's first fresh snapshot, including an empty list.
                                val (source, failure) = snapshotFlow {
                                    board.quickOpenSources.firstOrNull { it.host.id == host.id } to board.watchErrors[host.id]
                                }.first { (source, failure) -> source != null || failure != null }
                                if (failure != null) throw failure
                                source!!.terminals
                            }
                        }
                    } finally { deadline.cancel() }
                }
                    .find { it.address == address && it.state == "live" }
                if (!current()) return@launch
                if (terminal == null) {
                    prompt = if (fromInbox) Prompt.Problem(Message(R.string.call_terminal_gone_title),
                        Message(R.string.call_terminal_gone_body, listOf(address, host.name)))
                    else Prompt.Problem(Message(R.string.push_terminal_gone_title),
                        Message(R.string.push_terminal_gone_body, listOf(address, host.name)))
                    return@launch
                }
                // Detach the request before closing previous views so their navigation cannot cancel it.
                agentOpeningLink = null
                agentOpeningJob = null
                agentOpeningFromInbox = false
                agentOpening = null
                agentOpeningHostId = null
                val retained = callLink?.takeIf { it === target }
                if (retained != null) callLink = null
                returnToQuickOpenBase()
                if (retained != null) callLink = retained
                if (existing == null) ownCallLink(target)
                adopted = true
                val shared = pendingShare
                if (shared != null && terminal.agentStatus != null) {
                    chat(target, terminal, screen)
                    (screen as Screen.Chat).chat.pendingShare = shared
                    cancelShare()
                } else if (openChat) chat(target, terminal, screen) else show(target, terminal, screen)
                closeCallInbox()
            } catch (e: CoreException) {
                if (current()) prompt = problemFor(e)
            } finally {
                if (existing == null && !adopted) target.close()
                if (agentOpeningLink === target) agentOpeningLink = null
                if (request == agentOpenRequest) {
                    openingCall = null
                    agentOpening = null
                    agentOpeningHostId = null
                    agentOpeningJob = null
                }
            }
        }
    }

    /** Opens the caller in Chat without changing the saved Agent view. */
    fun openCallChat(entry: CallEntry) {
        val address = entry.call.terminal ?: return
        if (!callInboxOpen || openingCall == entry.key) return
        if (currentAgentKey == AgentKey(entry.host.id, address) && screen is Screen.Chat) {
            closeCallInbox()
            return
        }
        openAgentTarget(entry.host, address, openChat = true, callKey = entry.key)
    }

    /** Leaves the Chat (the Agent keeps running) for where it was opened. */
    fun closeChat() {
        userNavigation()
        val s = screen as? Screen.Chat ?: return
        s.chat.stop()
        if (s.chat.link === callLink) {
            ownCallLink(null)
        }
        screen = s.returnTo
    }

    /** Hidden composers and Changes share the Host's status feed without another watch. */
    private fun updateConversations(hostId: String, terminals: List<TerminalInfo>) {
        conversations.filterKeys { it.hostId == hostId }.values.forEach { it.update(terminals) }
        terminals.forEach { terminal -> taskChanges[AgentKey(hostId, terminal.address)]?.updateTerminal(terminal) }
    }

    private fun terminalOn(hostId: String): TerminalViewer? =
        (ownedScreen as? Screen.Terminal)?.viewer?.takeIf { it.link?.host?.id == hostId }

    fun back() = leaveForHosts(retainAgents = false)

    /** Host management is a destination above the retained home, not its connection owner. */
    fun showHosts() = leaveForHosts(retainAgents = true)

    private fun leaveForHosts(retainAgents: Boolean) {
        // Leaving Settings for a different destination releases its retained route normally.
        settingsReturnTo?.let { screen = it }
        settingsReturnTo = null
        settingsColdReturn = null
        pendingSettingsState = null
        val interruptedRestore = restoring
        userNavigation()
        // Cancelling a cold reconnect does not replace the last successfully viewed location.
        suppressLocationSave = interruptedRestore
        newTaskHostId = null
        threadTaskSelection = null
        if (prompt is Prompt.ConfirmSavedLaunch) prompt = null
        closeQuickOpen()
        pendingQuickOpen = null
        restoring = false
        cancelShare()
        (screen as? Screen.Chat)?.chat?.stop()
        ((screen as? Screen.Terminal)?.returnTo as? Screen.Chat)?.chat?.stop()
        viewing?.cancel()
        viewing = null
        ownCallLink(null)
        closeCallInbox()
        // Also under a Terminal or Chat opened from the Agents list.
        if (!retainAgents) closeAgents()
        screen = Screen.Hosts
        status?.cancel()
        status = null
        browseLink?.takeUnless { it in agentLinks.values }?.close()
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
                Message(R.string.incompatible_app_too_old),
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
            Message(R.string.key_install_write_failed),
        )
        is CoreException.InvalidHost -> Prompt.Problem(
            Message(R.string.host_edit_title), Message(R.string.host_edit_invalid_body),
        )
        is CoreException.AuthRejected -> Prompt.Problem(failed, Message(R.string.connect_auth_rejected))
        is CoreException.NoKey -> Prompt.Problem(failed, Message(R.string.connect_no_key))
        is CoreException.InvalidPrivateKey -> Prompt.Problem(
            Message(R.string.key_import_title), Message(R.string.key_import_invalid),
        )
        is CoreException.KeyInvalidated -> Prompt.Problem(
            Message(R.string.key_locked_title),
            Message(R.string.key_invalidated_body),
            recoverKey = true,
        )
        is CoreException.KeyLocked -> Prompt.Problem(
            Message(R.string.key_locked_title),
            Message(R.string.key_locked_body),
        )
        is CoreException.BiometricUnavailable -> Prompt.Problem(
            Message(R.string.key_biometric_unavailable_title),
            Message(R.string.key_biometric_unavailable_body),
        )
        is CoreException.BeaconNotInstalled -> Prompt.Problem(failed, Message(R.string.chat_status_no_beacon))
        else -> Prompt.Problem(Message(R.string.error_failed_title), errorMessage(e))
    }
}

/** Scrollback asked for when attaching a Terminal (what the desktop app keeps is 2000). */
const val SCROLLBACK_LINES = 2000u
