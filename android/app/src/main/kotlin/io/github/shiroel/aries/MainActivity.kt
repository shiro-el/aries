package io.github.shiroel.aries

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import io.github.shiroel.aries.security.AppLockGate
import io.github.shiroel.aries.security.AppLockSession
import io.github.shiroel.aries.security.DeviceAppUnlock
import io.github.shiroel.aries.security.LocalAppLock
import io.github.shiroel.aries.security.applyPrivacy
import io.github.shiroel.aries.security.setAppLockPrivacy
import io.github.shiroel.aries.ui.AriesTheme
import io.github.shiroel.aries.ui.ThemeSettings
import androidx.compose.runtime.remember
import io.github.shiroel.aries.ui.AriesApp
import io.github.shiroel.aries.ui.HostsModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : AppCompatActivity() {
    private val app get() = application as AriesApplication
    private lateinit var appUnlock: DeviceAppUnlock
    private lateinit var lockSession: AppLockSession
    private lateinit var sessions: HostsViewModel

    @Suppress("DEPRECATION") // Bundle.getParcelable is the API 28-compatible saved Intent reader.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.appLock.foregrounded()
        app.appLock.applyPrivacy(window)
        appUnlock = DeviceAppUnlock(this)
        lockSession = AppLockSession(app.appLock, appUnlock, ::resumeUnlockedUi)
        // Entry targets wait behind the lock and survive recreation until consumed.
        if (savedInstanceState == null) {
            pushTarget = PushTarget.from(intent)
            tileRequested = isTileIntent(intent)
            savedLaunchIntent = intent.takeIf { SavedLaunchTarget.from(it) != null }
            shareIntent = intent
            NotificationGroups.opened(this, intent)
        } else {
            pushTarget = PushTarget.from(savedInstanceState.getParcelable<Intent>("pending-push-target"))
            savedLaunchIntent = savedInstanceState.getParcelable<Intent>("pending-launch-target")
            tileRequested = savedInstanceState.getBoolean("pending-tile-target")
            shareIntent = savedInstanceState.getParcelable("pending-share-intent")
        }
        sessions = ViewModelProvider(this)[HostsViewModel::class.java]
        val push = sessions.push
        push.bind(this)
        model = sessions.model
        // After process death only IDs/settings are restored; recreation reuses the live route.
        if (savedInstanceState?.getBoolean("settings-open") == true && model?.screen == io.github.shiroel.aries.ui.Screen.Hosts) {
            model?.openSettings()
        }
        setContent {
            val model = sessions.model
            LaunchedEffect(model) { resumeModel() }
            val guidance = remember {
                io.github.shiroel.aries.guidance.AppGuidanceState(
                    getSharedPreferences("app-guidance", MODE_PRIVATE), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME,
                )
            }
            LaunchedEffect(guidance) { guidance.checkForUpdate() }
            val settings = remember { ThemeSettings.from(this) }
            val protectPreviews = app.appLock.enabled && app.appLock.hidePreviews
            SideEffect { window.setAppLockPrivacy(protectPreviews) }
            CompositionLocalProvider(LocalAppLock provides lockSession) {
                AriesTheme(settings) {
                    AppLockGate(lockSession, onLeave = { moveTaskToBack(true) }) {
                        AriesApp(model, settings = settings, guidance = guidance, allowNotifications = push::allowNotifications)
                    }
                }
            }
        }
        network = NetworkWatch(this) {
            if (!app.appLock.locked) model?.networkChanged()
        }.also { it.start() }
    }

    /** The screens' model, for network and foreground events. */
    private var model: HostsModel? = null
    private var network: NetworkWatch? = null

    /** Pending entry targets wait for the model and the resumed biometric authorizer. */
    private var pushTarget: PushTarget? = null
    private var tileRequested = false
    private var savedLaunchIntent: Intent? = null
    private fun isTileIntent(intent: Intent?) = intent?.action == AgentSurfaces.OPEN_ATTENTION &&
        intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    private var shareIntent: Intent? = null
    private var resumed = false
    private var needsForegroundRefresh = true

    private fun openShare() {
        val m = model ?: return
        if (!resumed || app.appLock.locked) return
        val incoming = shareIntent ?: return
        shareIntent = null
        m.receiveShare(incoming)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationGroups.opened(this, intent)
        intent.takeIf { SavedLaunchTarget.from(it) != null }?.let {
            pushTarget = null
            tileRequested = false
            savedLaunchIntent = it
            openEntryTarget()
        }
        if (isTileIntent(intent)) {
            savedLaunchIntent = null
            pushTarget = null
            tileRequested = true
            openEntryTarget()
        }
        shareIntent = intent
        openShare()
        // Warm start: the app was running when the notification was tapped.
        PushTarget.from(intent)?.let {
            savedLaunchIntent = null
            tileRequested = false
            pushTarget = it
            openEntryTarget()
        }
    }

    /** A target is consumed once; configuration changes and Recents do not rerun a launch. */
    private fun openEntryTarget() {
        val m = model ?: return
        if (!resumed || app.appLock.locked) return
        savedLaunchIntent?.let {
            savedLaunchIntent = null
            m.openSavedLaunch(this, it)
            m.start()
            return
        }
        if (tileRequested) {
            tileRequested = false
            m.openTile()
            m.start()
            return
        }
        val target = pushTarget
        pushTarget = null
        m.start(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // A language change recreates the activity; keep its Settings destination open.
        outState.putBoolean("settings-open", model?.screen == io.github.shiroel.aries.ui.Screen.Settings)
        outState.putParcelable("pending-push-target", pushTarget?.intent(this))
        // Preserve the received credential (or its absence); recreation must never sign an external entry.
        outState.putParcelable("pending-launch-target", savedLaunchIntent)
        outState.putBoolean("pending-tile-target", tileRequested)
        outState.putParcelable("pending-share-intent", shareIntent)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        app.appLock.foregrounded()
        app.appLock.applyPrivacy(window)
        super.onStart()
        needsForegroundRefresh = true
    }

    private fun resumeModel() {
        if (!resumed || app.appLock.locked) return
        val m = model ?: return
        val explicitSurface = savedLaunchIntent != null || tileRequested
        openShare()
        openEntryTarget()
        if (needsForegroundRefresh) {
            needsForegroundRefresh = false
            // A launch connects only its selected Host; do not open other biometric keys first.
            if (!explicitSurface) m.foregrounded()
        }
    }

    override fun onDestroy() {
        network?.stop()
        appUnlock.dispose()
        sessions.push.unbind()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resumeUnlockedUi()
    }

    private fun resumeUnlockedUi() {
        if (!resumed || app.appLock.locked) return
        app.appLock.applyPrivacy(window)
        app.vault.authorizer = BiometricUnlock(this)
        refreshLaunchShortcuts()
        resumeModel()
    }

    override fun onStop() {
        if (!isChangingConfigurations) app.appLock.backgrounded()
        super.onStop()
    }

    private fun refreshLaunchShortcuts() {
        sessions.refreshLaunchShortcuts()
    }

    override fun onPause() {
        resumed = false
        app.vault.authorizer = null
        super.onPause()
    }
}
