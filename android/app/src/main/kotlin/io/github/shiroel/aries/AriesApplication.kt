package io.github.shiroel.aries

import android.app.Application
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.AriesCoreInterface
import java.io.File

class AriesApplication : Application() {
    /** The Rust core; one per process. */
    val core: AriesCoreInterface by lazy {
        AriesCore(File(filesDir, "core").absolutePath, KeystoreVault())
    }
}
