package io.github.shiroel.aries.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.Incompatibility
import io.github.shiroel.aries.core.KeyInstall
import io.github.shiroel.aries.core.PublicKeyInfo
import io.github.shiroel.aries.core.StructureListener
import io.github.shiroel.aries.core.StructureWatch
import io.github.shiroel.aries.core.QrMatrix
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A message for the user: a string resource and its format arguments. */
data class Message(@StringRes val res: Int, val args: List<Any> = emptyList())

sealed interface Screen {
    data object Hosts : Screen
    data class Connecting(val host: Host) : Screen
    /** An open Host: its Spaces, Tabs and Panes. */
    data class Browse(val browser: HostBrowser) : Screen
    /** Every Host's Agents in one list. */
    data class Agents(val board: AgentBoard) : Screen
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

    /** Ask before removing [host] and everything stored for it. */
    data class ConfirmRemove(val host: Host) : Prompt

    /** Something the user must read; [title] and [body] are resources. */
    data class Problem(val title: Message, val body: Message) : Prompt
}

enum class AfterTrust { Connect, InstallKey }

/** UI state over the core API. Compose reads the `by mutableStateOf` fields. */
class HostsModel(private val core: AriesCoreInterface, private val scope: CoroutineScope) {
    var key by mutableStateOf<PublicKeyInfo?>(null)
        private set
    var hosts by mutableStateOf<List<Host>>(emptyList())
        private set
    var screen by mutableStateOf<Screen>(Screen.Hosts)
        private set
    var prompt by mutableStateOf<Prompt?>(null)
        private set

    /** The open Host's connection and structure watch, while [Screen.Browse] shows. */
    private var open: Pair<HostConnection, StructureWatch?>? = null

    init {
        guard {
            key = core.publicKey()
            hosts = core.hosts()
        }
    }

    fun createKey() = guard { key = core.ensureKey() }

    fun addHost(name: String, address: String, port: Int, user: String) = guard {
        val label = name.ifBlank { address }
        core.addHost(label, Endpoint.Ssh(address.trim(), port.toUShort(), user.trim()))
        hosts = core.hosts()
    }

    fun connect(host: Host) {
        screen = Screen.Connecting(host)
        scope.launch {
            try {
                val conn = core.connect(host.id)
                val browser = HostBrowser(host)
                open = conn to null
                screen = Screen.Browse(browser)
                try {
                    val watch = conn.watchStructure(object : StructureListener {
                        override fun onStructure(structure: HostStructure) {
                            scope.launch { browser.update(structure) }
                        }

                        override fun onClosed() {
                            scope.launch { browser.markClosed() }
                        }
                    })
                    if (open?.first === conn) {
                        open = conn to watch
                        status = watchStatus(conn, browser)
                    } else {
                        watch.stop()
                    }
                } catch (e: CoreException) {
                    if (open?.first === conn) open = null
                    conn.disconnect()
                    throw e
                }
            } catch (e: CoreException) {
                screen = Screen.Hosts
                prompt = when (e) {
                    is CoreException.HostKeyUnknown -> Prompt.TrustHostKey(host, e.fingerprint)
                    else -> problemFor(e)
                }
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
                Prompt.Problem(Message(R.string.key_install_done_title), Message(body, listOf(host.name)))
            } catch (e: CoreException) {
                when (e) {
                    is CoreException.HostKeyUnknown -> Prompt.TrustHostKey(host, e.fingerprint, AfterTrust.InstallKey)
                    else -> problemFor(e)
                }
            }
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
        }
    }

    fun dismiss() {
        prompt = null
    }

    /** Live Agent status of the open Host's Terminals; cancelled on [back]. */
    private var status: Job? = null

    private fun watchStatus(conn: HostConnection, browser: HostBrowser): Job = scope.launch {
        val watch = try {
            conn.watchTerminals()
        } catch (e: CoreException) {
            browser.markClosed()
            return@launch
        }
        try {
            // Each change replaces the list, so Agent status stays live.
            while (true) browser.updateTerminals(watch.next())
        } catch (e: CoreException) {
            browser.markClosed()
        } finally {
            watch.stop()
        }
    }

    /** Connections and watch jobs of [Screen.Agents], per Host id. */
    private val agentConns = mutableMapOf<String, HostConnection>()
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
                agentConns[host.id] = conn
                try {
                    val watch = conn.watchTerminals()
                    try {
                        while (true) board.update(host.id, watch.next())
                    } finally {
                        watch.stop()
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
        val conns = agentConns.values.toList()
        agentConns.clear()
        conns.forEach { scope.launch { it.disconnect() } }
    }

    fun back() {
        if (screen is Screen.Agents) closeAgents()
        screen = Screen.Hosts
        status?.cancel()
        status = null
        val (conn, watch) = open ?: return
        open = null
        watch?.stop()
        scope.launch { conn.disconnect() }
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
        is CoreException.Unreachable -> Prompt.Problem(failed, Message(R.string.connect_unreachable, listOf(e.detail)))
        is CoreException.Protocol -> Prompt.Problem(failed, Message(R.string.connect_protocol, listOf(e.detail)))
        else -> Prompt.Problem(failed, Message(R.string.connect_other, listOf(e.message ?: "")))
    }
}
