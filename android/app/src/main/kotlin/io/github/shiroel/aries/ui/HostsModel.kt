package io.github.shiroel.aries.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HostConnection
import io.github.shiroel.aries.core.HostStructure
import io.github.shiroel.aries.core.Incompatibility
import io.github.shiroel.aries.core.PublicKeyInfo
import io.github.shiroel.aries.core.StructureListener
import io.github.shiroel.aries.core.StructureWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A message for the user: a string resource and its format arguments. */
data class Message(@StringRes val res: Int, val args: List<Any> = emptyList())

sealed interface Screen {
    data object Hosts : Screen
    data class Connecting(val host: Host) : Screen
    /** An open Host: its Spaces, Tabs and Panes. */
    data class Browse(val browser: HostBrowser) : Screen
}

sealed interface Prompt {
    /** First connection: ask the user to confirm the Host key. */
    data class TrustHostKey(val host: Host, val fingerprint: String) : Prompt

    /** Something the user must read; [title] and [body] are resources. */
    data class Problem(val title: Message, val body: Message) : Prompt
}

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
                    if (open?.first === conn) open = conn to watch else watch.stop()
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
        hosts.find { it.id == prompt.host.id }?.let(::connect)
    }

    fun dismiss() {
        prompt = null
    }

    fun back() {
        screen = Screen.Hosts
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
        is CoreException.AuthRejected -> Prompt.Problem(failed, Message(R.string.connect_auth_rejected))
        is CoreException.NoKey -> Prompt.Problem(failed, Message(R.string.connect_no_key))
        is CoreException.Unreachable -> Prompt.Problem(failed, Message(R.string.connect_unreachable, listOf(e.detail)))
        is CoreException.Protocol -> Prompt.Problem(failed, Message(R.string.connect_protocol, listOf(e.detail)))
        else -> Prompt.Problem(failed, Message(R.string.connect_other, listOf(e.message ?: "")))
    }
}
