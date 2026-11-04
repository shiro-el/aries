package io.github.shiroel.aries

import android.os.Build
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.KeyVault
import io.github.shiroel.aries.core.VaultException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lets the user authorize one use of a biometric-protected Keystore key.
 * Blocks the calling thread until the user answers; returns the authorized
 * cipher, or null if the user cancelled. Never called on the main thread.
 */
fun interface BiometricAuthorizer {
    fun authorize(cipher: Cipher): Cipher?
}

/**
 * Seals the core's secrets (the SSH private key) with AES-256-GCM keys that
 * never leave the Android Keystore. Sealed form: `[12-byte IV][ciphertext+tag]`.
 *
 * One Keystore key per [KeyProtection] (ticket 01's measurements):
 * - [KeyProtection.PLAIN]: no user-authentication binding and no
 *   `setUnlockedDeviceRequired`, so it works on a locked phone and Choice
 *   buttons can answer from the notification.
 * - [KeyProtection.BIOMETRIC]: every use needs a strong biometric through
 *   [authorizer]. With no authorizer (no Activity in the foreground, as in a
 *   BroadcastReceiver) it fails at once with [VaultException.Locked] instead
 *   of being used quietly.
 */
class KeystoreVault(
    private val alias: String = "aries-vault",
    private val loadKey: ((KeyProtection) -> SecretKey)? = null,
) : KeyVault {
    @Volatile
    var authorizer: BiometricAuthorizer? = null

    override fun seal(plaintext: ByteArray, protection: KeyProtection): ByteArray = guarded {
        val cipher = synchronized(this) {
            try {
                encryptionCipher(protection)
            } catch (e: Exception) {
                if (!e.isInvalidated()) throw e
                // Only encryption can rotate an irrecoverable wrapping key.
                // Never retry decryption with a new key or discard SSH records.
                KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(aliasFor(protection)) }
                encryptionCipher(protection)
            }
        }
        val ready = authorized(cipher, protection)
        ready.iv + ready.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, protection: KeyProtection): ByteArray = guarded {
        require(sealed.size > IV_BYTES) { "sealed data too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(protection, create = false), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        authorized(cipher, protection).doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun authorized(cipher: Cipher, protection: KeyProtection): Cipher {
        if (protection == KeyProtection.PLAIN) return cipher
        val auth = authorizer ?: throw VaultException.Locked()
        check(Looper.myLooper() != Looper.getMainLooper()) { "biometric unlock on the main thread" }
        return auth.authorize(cipher) ?: throw VaultException.Locked()
    }

    private fun aliasFor(protection: KeyProtection) = when (protection) {
        KeyProtection.PLAIN -> alias
        KeyProtection.BIOMETRIC -> "$alias-biometric"
    }

    private fun encryptionCipher(protection: KeyProtection): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(protection)) }

    private fun key(protection: KeyProtection, create: Boolean = true): SecretKey {
        loadKey?.let { return it(protection) }
        val name = aliasFor(protection)
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(name, null) as SecretKey?)?.let { return it }
        if (!create) throw VaultException.Invalidated()
        val spec = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (protection == KeyProtection.BIOMETRIC) {
            spec.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            // Timeout 0: every use needs its own biometric, so a recent
            // screen unlock never lets the key work in the background.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(-1)
            }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        try {
            generator.init(spec.build())
            return generator.generateKey()
        } catch (e: InvalidAlgorithmParameterException) {
            // No secure lock screen or no enrolled biometric.
            throw VaultException.BiometricUnavailable(e.toString())
        } catch (e: IllegalStateException) {
            throw VaultException.BiometricUnavailable(e.toString())
        }
    }

    private inline fun guarded(block: () -> ByteArray): ByteArray =
        try {
            block()
        } catch (e: VaultException) {
            throw e
        } catch (e: UserNotAuthenticatedException) {
            throw VaultException.Locked()
        } catch (e: Exception) {
            if (e.isInvalidated() || e is AEADBadTagException) {
                throw VaultException.Invalidated()
            }
            throw VaultException.Failed(e.toString())
        }

    private fun Throwable.isInvalidated(): Boolean =
        generateSequence(this) { it.cause }.take(16).any { it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
