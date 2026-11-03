package io.github.shiroel.aries

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.shiroel.aries.ui.AriesApp
import io.github.shiroel.aries.ui.HostsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import io.github.shiroel.aries.core.CoreException

class MainActivity : ComponentActivity() {
    private val app get() = application as AriesApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold start from a notification or share; re-creations have already handled the intent.
        if (savedInstanceState == null) {
            pushTarget = PushTarget.from(intent)
            tileRequested = isTileIntent(intent)
            savedLaunchTarget = SavedLaunchTarget.from(intent)
            shareIntent = intent
            NotificationGroups.opened(this, intent)
        }
        val core = app.core
        val push = AndroidPushPlatform(this)
        setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, keyWork = Dispatchers.IO, pushPlatform = push,
                onSavedLaunchesChanged = ::refreshLaunchShortcuts).also { this@MainActivity.model = it } }
            LaunchedEffect(model) { resumeModel() }
            AriesApp(model)
        }
        network = NetworkWatch(this) { model?.networkChanged() }.also { it.start() }
    }

    /** The screens' model, for network and foreground events. */
    private var model: HostsModel? = null
    private var network: NetworkWatch? = null

    /** Pending entry targets wait for the model and the resumed biometric authorizer. */
    private var pushTarget: PushTarget? = null
    private var tileRequested = false
    private var savedLaunchTarget: SavedLaunchTarget? = null
    private fun isTileIntent(intent: Intent?) = intent?.action == AgentSurfaces.OPEN_ATTENTION &&
        intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    private var shareIntent: Intent? = null
    private var resumed = false
    private var needsForegroundRefresh = true

    private fun openShare() {
        val m = model ?: return
        if (!resumed) return
        val incoming = shareIntent ?: return
        shareIntent = null
        m.receiveShare(incoming)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationGroups.opened(this, intent)
        SavedLaunchTarget.from(intent)?.let {
            pushTarget = null
            tileRequested = false
            savedLaunchTarget = it
            openEntryTarget()
        }
        if (isTileIntent(intent)) {
            savedLaunchTarget = null
            pushTarget = null
            tileRequested = true
            openEntryTarget()
        }
        shareIntent = intent
        openShare()
        // Warm start: the app was running when the notification was tapped.
        PushTarget.from(intent)?.let {
            savedLaunchTarget = null
            tileRequested = false
            pushTarget = it
            openEntryTarget()
        }
    }

    /** A target is consumed once; configuration changes and Recents do not rerun a launch. */
    private fun openEntryTarget() {
        val m = model ?: return
        if (!resumed) return
        savedLaunchTarget?.let {
            savedLaunchTarget = null
            m.openSavedLaunch(it.hostId, it.launchId)
            return
        }
        if (tileRequested) {
            tileRequested = false
            m.openTile()
            return
        }
        val target = pushTarget ?: return
        pushTarget = null
        m.openPushedAgent(target.hostId, target.terminal, openChat = target.chat)
    }

    override fun onStart() {
        super.onStart()
        needsForegroundRefresh = true
    }

    private fun resumeModel() {
        if (!resumed) return
        val m = model ?: return
        if (needsForegroundRefresh) {
            needsForegroundRefresh = false
            // A launch connects only its selected Host; do not open other biometric keys first.
            if (savedLaunchTarget == null && !tileRequested) m.foregrounded()
        }
        openEntryTarget()
        openShare()
    }

    override fun onDestroy() {
        network?.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        app.vault.authorizer = BiometricUnlock(this)
        resumed = true
        refreshLaunchShortcuts()
        resumeModel()
    }

    private fun refreshLaunchShortcuts() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                LaunchShortcuts.sync(this@MainActivity, app.core)
            } catch (_: CoreException) {
                // A concurrent Host removal can be retried on the next foreground entry.
            } catch (_: IllegalStateException) {
                // The launcher service is unavailable until the user unlocks the phone.
            }
        }
    }

    override fun onPause() {
        resumed = false
        app.vault.authorizer = null
        super.onPause()
    }
}
