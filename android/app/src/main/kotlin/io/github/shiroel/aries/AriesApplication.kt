package io.github.shiroel.aries

import android.app.Application
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.AriesCoreInterface
import java.io.File

class AriesApplication : Application(), HasCore {
    /** Seals the SSH key. [MainActivity] installs its biometric prompt while resumed. */
    val vault = KeystoreVault()

    /** The Rust core; one per process. */
    override val core: AriesCoreInterface by lazy {
        AriesCore(File(filesDir, "core").absolutePath, vault)
    }
}
