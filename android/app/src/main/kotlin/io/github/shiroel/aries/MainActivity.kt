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

class MainActivity : ComponentActivity() {
    private val app get() = application as AriesApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold start from a notification or share; re-creations have already handled the intent.
        if (savedInstanceState == null) {
            pushTarget = PushTarget.from(intent)
            shareIntent = intent
            NotificationGroups.opened(this, intent)
        }
        val core = app.core
        val push = AndroidPushPlatform(this)
        setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, keyWork = Dispatchers.IO, pushPlatform = push).also { this@MainActivity.model = it } }
            LaunchedEffect(model) { resumeModel() }
            AriesApp(model)
        }
        network = NetworkWatch(this) { model?.networkChanged() }.also { it.start() }
    }

    /** The screens' model, for network and foreground events. */
    private var model: HostsModel? = null
    private var network: NetworkWatch? = null

    /** A tapped notification's Agent, until the model exists and the activity is in front. */
    private var pushTarget: PushTarget? = null
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
        shareIntent = intent
        openShare()
        // Warm start: the app was running when the notification was tapped.
        PushTarget.from(intent)?.let {
            pushTarget = it
            openPushTarget()
        }
    }

    /** Opens the Agent once both are ready; the biometric prompt needs a resumed activity. */
    private fun openPushTarget() {
        val m = model ?: return
        if (!resumed) return
        val target = pushTarget ?: return
        pushTarget = null
        m.openPushedAgent(target.hostId, target.terminal)
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
            m.foregrounded()
        }
        openPushTarget()
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
        resumeModel()
    }

    override fun onPause() {
        resumed = false
        app.vault.authorizer = null
        super.onPause()
    }
}
