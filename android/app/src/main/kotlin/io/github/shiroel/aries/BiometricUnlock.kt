package io.github.shiroel.aries

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * Shows the system biometric prompt over [activity] for one Keystore
 * operation. Only installed while the Activity is resumed, so background
 * callers never see a prompt and get [io.github.shiroel.aries.core.VaultException.Locked].
 */
class BiometricUnlock(private val activity: Activity) : BiometricAuthorizer {
    override fun authorize(cipher: Cipher): Cipher? {
        val done = CountDownLatch(1)
        var result: Cipher? = null
        val cancel = CancellationSignal()
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                done.countDown()
                return@runOnUiThread
            }
            BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.key_biometric_prompt_title))
                .setNegativeButton(activity.getString(R.string.cancel_button), activity.mainExecutor) { _, _ ->
                    done.countDown()
                }
                .build()
                .authenticate(
                    BiometricPrompt.CryptoObject(cipher),
                    cancel,
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                            result = r.cryptoObject?.cipher
                            done.countDown()
                        }

                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            done.countDown()
                        }
                    },
                )
        }
        val completed = try {
            done.await(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) cancel.cancel()
        return result
    }

    private companion object {
        const val PROMPT_TIMEOUT_SECONDS = 120L
    }
}
