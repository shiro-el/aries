package io.github.shiroel.aries

import android.app.Application
import androidx.work.Configuration
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.AriesCoreInterface
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

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

    /** Seals the SSH key. [MainActivity] installs its biometric prompt while resumed. */
    val vault = KeystoreVault()

    /** The Rust core; one per process. */
    override val core: AriesCoreInterface by lazy {
        AriesCore(File(filesDir, "core").absolutePath, vault)
    }
}
