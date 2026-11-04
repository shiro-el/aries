package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.HerdrCoreState
import io.github.shiroel.aries.core.HerdrInstallListener
import io.github.shiroel.aries.core.HerdrInstallStep
import io.github.shiroel.aries.core.Host
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-Host installer, reachable through settings without a core connection. */
class HerdrInstallModel(
    private val core: AriesCoreInterface,
    private val scope: CoroutineScope,
    private val fetcher: BeaconFetcher,
    private val keyWork: CoroutineContext = EmptyCoroutineContext,
) {
    var host by mutableStateOf<Host?>(null)
        private set
    var state by mutableStateOf<HerdrCoreState?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var confirm by mutableStateOf(false)
        private set
    var step by mutableStateOf<HerdrInstallStep?>(null)
        private set
    var output by mutableStateOf("")
        private set
    var problem by mutableStateOf<Message?>(null)
        private set

    fun load(host: Host) {
        if (busy) return
        this.host = host
        if (host.endpoint !is Endpoint.Ssh) return
        refresh()
    }

    fun refresh() {
        val host = host ?: return
        if (busy) return
        busy = true
        problem = null
        step = null
        output = ""
        scope.launch {
            try {
                state = withContext(keyWork) { core.herdrCoreStatus(host.id) }
            } catch (e: CoreException) {
                problem = error(e)
            } finally {
                busy = false
            }
        }
    }

    fun askInstall() { if (!busy) confirm = true }
    fun cancel() { confirm = false }

    /** Confirmation is required even if a caller invokes this directly. */
    fun install() {
        if (!confirm || busy) return
        confirm = false
        val host = host ?: return
        busy = true
        problem = null
        output = ""
        step = HerdrInstallStep.DETECT
        scope.launch {
            try {
                state = withContext(keyWork) {
                    core.installHerdrCore(host.id, fetcher, object : HerdrInstallListener {
                        override fun progress(step: HerdrInstallStep, output: String) {
                            scope.launch {
                                if (busy) {
                                    this@HerdrInstallModel.step = step
                                    if (output.isNotBlank()) this@HerdrInstallModel.output =
                                        (this@HerdrInstallModel.output + output + "\n").takeLast(16_384)
                                }
                            }
                        }
                    })
                }
                step = HerdrInstallStep.DONE
            } catch (e: CoreException) {
                problem = error(e)
            } finally {
                busy = false
            }
        }
    }

    private fun error(e: CoreException): Message = when (e) {
        is CoreException.HerdrChecksum -> Message(R.string.herdr_install_checksum, listOf(e.expected, e.actual))
        is CoreException.HerdrInstall -> Message(R.string.herdr_install_failed, listOf(e.step, e.detail))
        else -> problemFor(e).body
    }
}
