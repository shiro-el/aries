package io.github.shiroel.aries

import android.app.Application
import androidx.work.Configuration
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.AriesCoreInterface
import java.io.File

class AriesApplication : Application(), HasCore, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        LastKnownStates.schedule(this)
    }

    /** Seals the SSH key. [MainActivity] installs its biometric prompt while resumed. */
    val vault = KeystoreVault()

    /** The Rust core; one per process. */
    override val core: AriesCoreInterface by lazy {
        AriesCore(File(filesDir, "core").absolutePath, vault)
    }
}
