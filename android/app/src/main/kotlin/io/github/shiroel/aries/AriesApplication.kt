package io.github.shiroel.aries

import android.app.Application
import android.content.res.Configuration as ResourceConfiguration
import androidx.work.Configuration
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.AriesCoreInterface
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AriesApplication : Application(), HasCore, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        LastKnownStates.schedule(this)
        updateHomeWidgetObservation()
    }

    private val homeWidgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var homeWidgetUpdates: Job? = null

    /** Widget removal releases the local watch; normal launches with no widgets leave core lazy. */
    fun updateHomeWidgetObservation() {
        if (HomeWidgets.hasWidgets(this)) {
            if (homeWidgetUpdates?.isActive != true) homeWidgetUpdates = HomeWidgets.observe(this, core, homeWidgetScope)
        } else {
            homeWidgetUpdates?.cancel()
            homeWidgetUpdates = null
        }
    }

    override fun onConfigurationChanged(newConfig: ResourceConfiguration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguageSurfaces()
    }

    /** Configuration changes must republish strings without connecting to a Host. */
    fun refreshLanguageSurfaces() {
        WatchPhone.languageChanged()
        if (HomeWidgets.hasWidgets(this)) {
            homeWidgetScope.launch { HomeWidgets.update(this@AriesApplication, core) }
        }
    }

    private val companionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Only the policy is persisted; an unlocked app session lives for this process. */
    val appLock by lazy { io.github.shiroel.aries.security.AppLock.from(this) }

    /** Seals the SSH key. [MainActivity] installs its biometric prompt while resumed. */
    val vault = KeystoreVault()

    /** The Rust core; one per process. */
    override val core: AriesCoreInterface by lazy {
        AriesCore(File(filesDir, "core").absolutePath, vault).also {
            WatchPhone.start(this, it, companionScope)
        }
    }

    override fun onTerminate() {
        companionScope.cancel()
        homeWidgetScope.cancel()
        super.onTerminate()
    }
}
