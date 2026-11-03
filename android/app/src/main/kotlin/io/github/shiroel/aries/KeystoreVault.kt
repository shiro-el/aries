package io.github.shiroel.aries

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.shiroel.aries.core.KeyVault
import io.github.shiroel.aries.core.VaultException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the core's secrets (the SSH private key) with an AES-256-GCM key that
 * never leaves the Android Keystore. Sealed form: `[12-byte IV][ciphertext+tag]`.
 *
 * The key requires no user authentication yet; the key policy ticket adds
 * the biometric option.
 */
class KeystoreVault(private val alias: String = "aries-vault") : KeyVault {

    override fun seal(plaintext: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray = guarded {
        require(sealed.size > IV_BYTES) { "sealed data too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private inline fun guarded(block: () -> ByteArray): ByteArray =
        try {
            block()
        } catch (e: Exception) {
            throw VaultException.Failed(e.toString())
        }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
