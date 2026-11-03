package io.github.shiroel.aries.security

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.shiroel.aries.R

/** Non-crypto app authentication, independent of the SSH key's stronger Keystore policy. */
class DeviceAppUnlock(private val activity: ComponentActivity) : AppAuthenticator {
    private var pending: ((UnlockResult) -> Unit)? = null
    private var signal: CancellationSignal? = null
    private val credential = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        complete(if (it.resultCode == Activity.RESULT_OK) UnlockResult.SUCCESS else UnlockResult.CANCELLED)
    }

    @Suppress("DEPRECATION") // API 28 credential fallback and API 29's combined prompt.
    override fun authenticate(result: (UnlockResult) -> Unit) {
        if (pending != null) return
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) {
            result(UnlockResult.UNAVAILABLE)
            return
        }
        pending = result
        if (Build.VERSION.SDK_INT == 28) {
            // Android 9 has no combined BiometricPrompt + device credential API.
            val intent = keyguard.createConfirmDeviceCredentialIntent(
                activity.getString(R.string.app_lock_screen_title),
                activity.getString(R.string.app_lock_screen_body),
            )
            if (intent == null) complete(UnlockResult.UNAVAILABLE) else credential.launch(intent)
            return
        }
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.app_lock_screen_title))
            .setSubtitle(activity.getString(R.string.app_lock_screen_body))
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
        } else {
            builder.setDeviceCredentialAllowed(true)
        }
        val cancellation = CancellationSignal().also { signal = it }
        builder.build().authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (signal === cancellation) complete(UnlockResult.SUCCESS)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (signal !== cancellation) return
                complete(when (errorCode) {
                    BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> UnlockResult.CANCELLED
                    else -> UnlockResult.FAILED
                })
            }
            // A rejected fingerprint/face is not success; the system keeps the prompt open.
        })
    }

    /** Old Activity callbacks must never authenticate a replacement Activity. */
    fun dispose() {
        val cancellation = signal
        complete(UnlockResult.CANCELLED)
        cancellation?.cancel()
    }

    private fun complete(result: UnlockResult) {
        val callback = pending ?: return
        pending = null
        signal = null
        callback(result)
    }
}
