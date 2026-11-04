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
        ThemeSettings.from(this).applyNightMode()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.appLock.foregrounded()
        app.appLock.applyPrivacy(window)
        appUnlock = DeviceAppUnlock(this)
        lockSession = AppLockSession(app.appLock, appUnlock, ::resumeUnlockedUi)
        // Entry targets wait behind the lock and survive recreation until consumed.
        if (savedInstanceState == null) {
            pushIntent = intent.takeIf { PushTarget.from(it) != null }
            tileRequested = isTileIntent(intent)
            savedLaunchIntent = intent.takeIf { SavedLaunchTarget.from(it) != null }
            shareIntent = intent
        } else {
            pushIntent = savedInstanceState.getParcelable<Intent>("pending-push-target")
            savedLaunchIntent = savedInstanceState.getParcelable<Intent>("pending-launch-target")
            tileRequested = savedInstanceState.getBoolean("pending-tile-target")
            shareIntent = savedInstanceState.getParcelable("pending-share-intent")
        }
        // AndroidViewModelFactory.getInstance caches the first Application in the JVM.
        // Bind creation to this Activity's Application, including when a test replaces it.
        sessions = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[HostsViewModel::class.java]
        val push = sessions.push
        push.bind(this)
        model = sessions.model
        // A retained model already owns its route; a fresh model queues only IDs until unlock.
        model?.restoreSettingsState(savedInstanceState?.getBundle("settings-route"))
        setContent {
            val model = sessions.model
            LaunchedEffect(model) { resumeModel() }
            val guidance = sessions.guidance
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
            model?.networkChanged()
        }.also { it.start() }
    }

    /** The screens' model, for network and foreground events. */
    private var model: HostsModel? = null
    private var network: NetworkWatch? = null

    /** Pending entry targets wait for the model and the resumed biometric authorizer. */
    private var pushIntent: Intent? = null
    private var tileRequested = false
    private var savedLaunchIntent: Intent? = null
    private fun isTileIntent(intent: Intent?) = intent?.action == AgentSurfaces.OPEN_ATTENTION &&
        intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    private var shareIntent: Intent? = null
    private var resumed = false

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
        intent.takeIf { SavedLaunchTarget.from(it) != null }?.let {
            pushIntent = null
            tileRequested = false
            savedLaunchIntent = it
            openEntryTarget()
        }
        if (isTileIntent(intent)) {
            savedLaunchIntent = null
            pushIntent = null
            tileRequested = true
            openEntryTarget()
        }
        shareIntent = intent
        openShare()
        // Warm start: the app was running when the notification was tapped.
        PushTarget.from(intent)?.let {
            savedLaunchIntent = null
            tileRequested = false
            pushIntent = intent
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
            m.start(layout = ThemeSettings.from(this).layout)
            return
        }
        if (tileRequested) {
            tileRequested = false
            m.openTile()
            m.start(layout = ThemeSettings.from(this).layout)
            return
        }
        val incoming = pushIntent
        pushIntent = null
        val target = PushTarget.from(incoming)
        if (target != null && incoming != null) NotificationGroups.opened(this, incoming)
        m.start(target, layout = ThemeSettings.from(this).layout)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("settings-route", model?.saveSettingsState())
        outState.putParcelable("pending-push-target", pushIntent)
        // Preserve the received credential (or its absence); recreation must never sign an external entry.
        outState.putParcelable("pending-launch-target", savedLaunchIntent)
        outState.putBoolean("pending-tile-target", tileRequested)
        // openShare hands ownership to the Agent picker; its unconsumed envelope must survive death too.
        outState.putParcelable("pending-share-intent", shareIntent ?: model?.pendingShareIntent)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        app.appLock.foregrounded()
        app.appLock.applyPrivacy(window)
        super.onStart()
    }

    private fun resumeModel() {
        if (!resumed || app.appLock.locked) return
        val m = model ?: return
        val explicitSurface = savedLaunchIntent != null || tileRequested
        openShare()
        openEntryTarget()
        if (sessions.foregroundRefreshPending) {
            sessions.foregroundRefreshPending = false
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
        NotificationGroups.foregrounded(this)
        app.vault.authorizer = BiometricUnlock(this)
        refreshLaunchShortcuts()
        resumeModel()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            sessions.foregroundRefreshPending = true
            app.appLock.backgrounded()
        }
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
