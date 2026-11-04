package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.BeaconUpdate
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.Linger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
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
    /** Called after the Beacon was removed (its push pairing is queued for revocation). */
    private val onRemoved: () -> Unit = {},
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
    /** What connecting did about the Beacon's version. */
    var update by mutableStateOf<BeaconUpdate>(BeaconUpdate.NotNeeded)
        private set
    var autoUpdate by mutableStateOf(true)
        private set

    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Message?>(null)
        private set

    /** The Host has a Beacon installed (as last read). */
    val installed: Boolean
        get() = (ui as? BeaconUi.Known)?.state is BeaconState.Installed

    /** The release this app installs. */
    val pinnedVersion: String get() = core.beaconRelease().version

    /**
     * Reads the Host's Beacon when a Host is connected, updating an
     * out-of-date one unless automatic updates are off.
     */
    fun load(host: Host) {
        if (busy) return
        if (this.host?.id != host.id) ui = BeaconUi.Loading
        this.host = host
        autoUpdate = core.beaconAutoUpdate()
        update = BeaconUpdate.NotNeeded
        if (host.endpoint !is Endpoint.Ssh) {
            ui = BeaconUi.Unavailable(Message(R.string.beacon_needs_ssh))
            return
        }
        run {
            val check = onKey { core.checkBeacon(host.id, fetcher) }
            update = check.update
            (check.update as? BeaconUpdate.Updated)?.let { explainLinger(host, it.install.linger) }
            check.state
        }
    }

    fun install() {
        val host = host ?: return
        if (busy || host.endpoint !is Endpoint.Ssh) return
        run {
            val result = onKey { core.installBeacon(host.id, fetcher) }
            update = BeaconUpdate.NotNeeded
            explainLinger(host, result.linger)
            onKey { core.beaconStatus(host.id) }
        }
    }

    fun changeAutoUpdate(on: Boolean) {
        if (busy) return
        problem = null
        try {
            core.setBeaconAutoUpdate(on)
            autoUpdate = on
        } catch (e: CoreException) {
            problem = problemFor(e).body
        }
    }

    private fun explainLinger(host: Host, linger: Linger) {
        prompt = when (linger) {
            is Linger.Failed -> Prompt.Problem(
                Message(R.string.beacon_linger_title),
                Message(R.string.beacon_linger_body, listOf(linger.reason, userOf(host))),
            )
            Linger.LoginSession, Linger.Enabled -> return
        }
    }

    fun askRemove() {
        if (busy) return
        confirmRemove = true
    }

    fun remove() {
        confirmRemove = false
        val host = host ?: return
        if (busy || host.endpoint !is Endpoint.Ssh) return
        run {
            val state = onKey {
                core.uninstallBeacon(host.id)
                core.beaconStatus(host.id)
            }
            onRemoved()
            state
        }
    }

    fun dismiss() {
        prompt = null
        confirmRemove = false
    }

    private suspend fun <T> onKey(block: suspend () -> T): T = withContext(keyWork) { block() }

    private fun run(block: suspend () -> BeaconState) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                ui = BeaconUi.Known(block())
            } catch (e: CancellationException) {
                if (ui !is BeaconUi.Known) ui = BeaconUi.Unavailable(Message(R.string.beacon_status_unknown))
                throw e
            } catch (e: CoreException) {
                problem = beaconProblem(e).body
                if (ui !is BeaconUi.Known) ui = BeaconUi.Unavailable(Message(R.string.beacon_status_unknown))
            } finally {
                busy = false
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
