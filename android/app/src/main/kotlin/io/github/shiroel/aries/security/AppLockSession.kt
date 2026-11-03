package io.github.shiroel.aries.security

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A system authentication boundary; fakes replace the OS prompt, never the lock policy. */
fun interface AppAuthenticator {
    fun authenticate(result: (UnlockResult) -> Unit)
}

enum class UnlockResult { SUCCESS, CANCELLED, FAILED, UNAVAILABLE }

class AppLockSession(
    val lock: AppLock,
    private val authenticator: AppAuthenticator,
    private val onUnlocked: () -> Unit = {},
) {
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<UnlockResult?>(null)
        private set

    fun unlock() = authenticate(enable = false)
    fun enable() = authenticate(enable = true)

    fun disable() {
        if (busy) return
        notice = null
        lock.disable()
    }

    private fun authenticate(enable: Boolean) {
        if (busy) return
        busy = true
        notice = null
        authenticator.authenticate { result ->
            busy = false
            if (result == UnlockResult.SUCCESS) {
                if (enable) lock.enableAfterAuthentication() else lock.authenticationSucceeded()
                onUnlocked()
            } else notice = result
        }
    }
}
