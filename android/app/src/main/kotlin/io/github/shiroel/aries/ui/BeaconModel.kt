package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.Linger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Beacon section of a Host shows. */
sealed interface BeaconUi {
    data object Loading : BeaconUi
    data object Working : BeaconUi
    data class Known(val state: BeaconState) : BeaconUi
    data class Unavailable(val reason: Message) : BeaconUi
}

/**
 * Install / inspect / remove the Beacon on the Host being shown. Every call
 * signs in to the Host, so it runs in [keyWork] (see [HostsModel]).
 */
class BeaconModel(
    private val core: AriesCoreInterface,
    private val scope: CoroutineScope,
    private val fetcher: BeaconFetcher,
    private val keyWork: CoroutineContext = EmptyCoroutineContext,
) {
    var host by mutableStateOf<Host?>(null)
        private set
    var ui by mutableStateOf<BeaconUi>(BeaconUi.Loading)
        private set
    var prompt by mutableStateOf<Prompt?>(null)
        private set
    var confirmRemove by mutableStateOf(false)
        private set

    /** Reads the Host's Beacon version; called when a Host is connected. */
    fun load(host: Host) {
        this.host = host
        if (host.endpoint !is Endpoint.Ssh) {
            ui = BeaconUi.Unavailable(Message(R.string.beacon_needs_ssh))
            return
        }
        ui = BeaconUi.Loading
        run { onKey { core.beaconStatus(host.id) } }
    }

    fun install() {
        val host = host ?: return
        ui = BeaconUi.Working
        run {
            val result = onKey { core.installBeacon(host.id, fetcher) }
            (result.linger as? Linger.Failed)?.let {
                prompt = Prompt.Problem(
                    Message(R.string.beacon_linger_title),
                    Message(R.string.beacon_linger_body, listOf(it.reason, userOf(host))),
                )
            }
            onKey { core.beaconStatus(host.id) }
        }
    }

    fun askRemove() {
        confirmRemove = true
    }

    fun remove() {
        confirmRemove = false
        val host = host ?: return
        ui = BeaconUi.Working
        run {
            onKey {
                core.uninstallBeacon(host.id)
                core.beaconStatus(host.id)
            }
        }
    }

    fun dismiss() {
        prompt = null
        confirmRemove = false
    }

    private suspend fun <T> onKey(block: suspend () -> T): T = withContext(keyWork) { block() }

    private fun run(block: suspend () -> BeaconState) {
        scope.launch {
            ui = try {
                BeaconUi.Known(block())
            } catch (e: CoreException) {
                prompt = beaconProblem(e)
                BeaconUi.Unavailable(Message(R.string.beacon_status_unknown))
            }
        }
    }
}

fun userOf(host: Host): String = (host.endpoint as? Endpoint.Ssh)?.user ?: ""

fun beaconProblem(e: CoreException): Prompt.Problem {
    val title = Message(R.string.beacon_failed_title)
    return when (e) {
        is CoreException.BeaconChecksum ->
            Prompt.Problem(title, Message(R.string.beacon_checksum_body, listOf(e.expected, e.actual)))
        is CoreException.UnsupportedHost ->
            Prompt.Problem(title, Message(R.string.beacon_unsupported_host_body, listOf(e.os, e.arch)))
        is CoreException.BeaconUnavailable ->
            Prompt.Problem(title, Message(R.string.beacon_unavailable_body, listOf(e.os, e.arch)))
        is CoreException.BeaconDownload ->
            Prompt.Problem(title, Message(R.string.beacon_download_body, listOf(e.detail)))
        is CoreException.BeaconStep ->
            Prompt.Problem(title, Message(R.string.beacon_step_body, listOf(e.step, e.detail)))
        else -> problemFor(e)
    }
}
