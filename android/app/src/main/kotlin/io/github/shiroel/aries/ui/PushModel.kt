package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.HttpClient
import io.github.shiroel.aries.core.PushDevice
import io.github.shiroel.aries.core.PushPolicy
import io.github.shiroel.aries.core.PushProgress
import io.github.shiroel.aries.core.PushVerificationState
import io.github.shiroel.aries.core.PushStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** What the phone offers for push: this build's default Relay, the FCM token, the permission. */
interface PushPlatform {
    /**
     * The default Relay URL of this build (`ARIES_RELAY_URL`); `null` when none is configured.
     * The user can use another (self-hosted) Relay per Host instead.
     */
    val relayUrl: String?

    /** This phone's FCM registration token; throws [PushUnavailable]. */
    suspend fun deviceToken(): String

    /** Asks for the notification permission where Android needs it (13+); `true` when allowed. */
    suspend fun allowNotifications(): Boolean
}

class PushUnavailable(reason: String) : Exception(reason)

/** A build without push (and the default in tests that do not care). */
object NoPush : PushPlatform {
    override val relayUrl: String? = null
    override suspend fun deviceToken(): String = throw PushUnavailable("no push in this build")
    override suspend fun allowNotifications(): Boolean = false
}

/** One step of turning push on, as the section lists it. */
enum class StepState { Running, Done, Failed }

sealed interface PushUi {
    data object Loading : PushUi
    data object Off : PushUi

    /** Turning push on: the steps reached so far. */
    data class Enabling(val steps: List<Pair<PushStep, StepState>>) : PushUi

    data class On(val relayUrl: String) : PushUi
    data object Working : PushUi
}

/** Turn push on/off for the Host being shown (its Beacon must be installed). */
class PushModel(
    private val core: AriesCoreInterface,
    private val scope: CoroutineScope,
    private val platform: PushPlatform,
    private val http: HttpClient,
    /** Runs the calls that sign in to the Host (see [HostsModel]). */
    private val keyWork: CoroutineContext = EmptyCoroutineContext,
) {
    var host by mutableStateOf<Host?>(null)
        private set
    var ui by mutableStateOf<PushUi>(PushUi.Loading)
        private set

    var verificationState by mutableStateOf<PushVerificationState?>(null)
        private set

    /** Also exposes failed background token rotations when reopening settings. */
    fun refreshVerification() {
        verificationState = host?.let { runCatching { core.pushVerificationState(it.id) }.getOrNull() }
    }

    /** Why the last action failed, shown under the section. */
    var problem by mutableStateOf<Message?>(null)
        private set

    /** Something to tell after an action that worked. */
    var notice by mutableStateOf<Message?>(null)
        private set

    /** The Relay URL being typed: to turn push on with, or to change to. */
    var relayDraft by mutableStateOf("")

    /** Push is on and the user is choosing another Relay. */
    var changingRelay by mutableStateOf(false)
        private set

    /** `true` when this build has a default Relay (otherwise the user must give one). */
    val hasDefaultRelay: Boolean get() = platform.relayUrl != null

    /** What the Host's Beacon notifies about; `null` until read from the Host (over SSH). */
    var policy by mutableStateOf<PushPolicy?>(null)
        private set

    /** Reading or saving [policy] on the Host. */
    var policyBusy by mutableStateOf(false)
        private set

    /** Why reading or saving [policy] failed. */
    var policyProblem by mutableStateOf<Message?>(null)
        private set

    fun load(host: Host) {
        this.host = host
        policy = null
        policyProblem = null
        problem = null
        notice = null
        changingRelay = false
        refresh()
        relayDraft = (ui as? PushUi.On)?.relayUrl ?: platform.relayUrl.orEmpty()
    }

    private fun refresh() {
        val host = host ?: return
        refreshVerification()
        ui = try {
            core.pushPairing(host.id)?.let { PushUi.On(it.relayUrl) }
                ?: PushUi.Off
        } catch (e: CoreException) {
            PushUi.Off
        }
        if (ui is PushUi.On && policy == null && policyProblem == null) loadPolicy()
    }

    /** Reads the Host's push policy (again, after a failure). */
    fun loadPolicy() {
        val host = host ?: return
        if (policyBusy) return
        policyBusy = true
        policyProblem = null
        scope.launch {
            try {
                val read = withContext(keyWork) { core.pushPolicy(host.id) }
                if (this@PushModel.host?.id == host.id) policy = read
            } catch (e: CoreException) {
                policyProblem = policyFailed(e)
            } finally {
                policyBusy = false
            }
        }
    }

    /** Saves the policy with one option changed; on failure the old one stays. */
    fun changePolicy(change: (PushPolicy) -> PushPolicy) {
        val host = host ?: return
        val old = policy ?: return
        if (policyBusy) return
        policyBusy = true
        policyProblem = null
        scope.launch {
            try {
                val saved = withContext(keyWork) { core.setPushPolicy(host.id, change(old)) }
                if (this@PushModel.host?.id == host.id) policy = saved
            } catch (e: CoreException) {
                policyProblem = policyFailed(e)
            } finally {
                policyBusy = false
            }
        }
    }

    /** Shows the Relay URL field for a Host whose push is on. */
    fun changeRelay() {
        val on = ui as? PushUi.On ?: return
        relayDraft = on.relayUrl
        problem = null
        notice = null
        changingRelay = true
    }

    fun cancelRelayChange() {
        changingRelay = false
        (ui as? PushUi.On)?.let { relayDraft = it.relayUrl }
    }

    /**
     * Turns push on with the Relay in [relayDraft] or, when push is on, moves
     * the pairing to it (the core unpairs the old Relay once the new one works).
     */
    fun enable() {
        val host = host ?: return
        val relay = relayDraft.trim()
        if (relay.isEmpty()) return
        val before = (ui as? PushUi.On)?.relayUrl
        changingRelay = false
        problem = null
        notice = null
        ui = PushUi.Enabling(emptyList())
        scope.launch {
            if (!platform.allowNotifications()) {
                problem = Message(R.string.push_permission_denied)
                refresh()
                return@launch
            }
            val token = try {
                platform.deviceToken()
            } catch (e: PushUnavailable) {
                problem = Message(R.string.push_unavailable, listOf(e.message ?: ""))
                refresh()
                return@launch
            }
            val progress = object : PushProgress {
                override fun onStep(step: PushStep) {
                    scope.launch { advance(step) }
                }
            }
            try {
                withContext(keyWork) { core.enablePush(host.id, relay, PushDevice("fcm", token), http, progress) }
                notice = Message(R.string.push_test_sent)
                refresh()
            } catch (e: CoreException) {
                problem = pushProblem(e)
                // Paired but the test failed: push is on. Otherwise keep the
                // steps on screen, the failed one marked.
                finishSteps(StepState.Failed)
                val now = runCatching { core.pushPairing(host.id) }.getOrNull()
                if (now != null) refresh()
                // A failed change keeps the old Relay: keep the typed URL open to fix.
                if (before != null && now?.relayUrl == before) {
                    relayDraft = relay
                    changingRelay = true
                }
            }
        }
    }

    fun sendTest() {
        val host = host ?: return
        problem = null
        notice = null
        scope.launch {
            try {
                withContext(keyWork) { core.sendTestPush(host.id) }
                notice = Message(R.string.push_test_sent)
            } catch (e: CoreException) {
                problem = pushProblem(e)
            }
        }
    }

    fun disable() {
        val host = host ?: return
        problem = null
        notice = null
        ui = PushUi.Working
        scope.launch {
            try {
                val off = withContext(keyWork) { core.disablePush(host.id, http) }
                if (!off.hostCleared) notice = Message(R.string.push_off_host_unreachable)
            } catch (e: CoreException) {
                problem = pushProblem(e)
            }
            refresh()
        }
    }

    private fun advance(step: PushStep) {
        val steps = (ui as? PushUi.Enabling)?.steps ?: return
        ui = PushUi.Enabling(steps.map { (s, st) -> s to if (st == StepState.Running) StepState.Done else st } + (step to StepState.Running))
    }

    private fun finishSteps(last: StepState) {
        val steps = (ui as? PushUi.Enabling)?.steps ?: return
        ui = PushUi.Enabling(steps.map { (s, st) -> s to if (st == StepState.Running) last else st })
    }
}

fun stepLabel(step: PushStep): Int = when (step) {
    PushStep.VERIFY -> R.string.push_step_verify
    PushStep.REGISTER -> R.string.push_step_register
    PushStep.PAIR -> R.string.push_step_pair
    PushStep.TEST -> R.string.push_step_test
    PushStep.UNPAIR -> R.string.push_step_unpair
}

fun pushProblem(e: CoreException): Message = when (e) {
    is CoreException.PushFailed -> Message(R.string.push_failed, listOf(Message(stepLabel(e.step)), e.detail))
    else -> problemFor(e).body
}

private fun policyFailed(e: CoreException): Message = when (e) {
    is CoreException.BeaconStep -> Message(R.string.push_policy_failed, listOf(e.detail))
    else -> problemFor(e).body
}
