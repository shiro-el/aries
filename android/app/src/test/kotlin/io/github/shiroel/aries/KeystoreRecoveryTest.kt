package io.github.shiroel.aries

import android.security.keystore.KeyPermanentlyInvalidatedException
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.VaultException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.spec.SecretKeySpec

/** Vault boundary: provider-wrapped invalidation must survive the UniFFI callback. */
@RunWith(RobolectricTestRunner::class)
class KeystoreRecoveryTest {
    @Test fun permanentInvalidationOffersRecoveryWhileCancellationStaysLocked() = runBlocking { withContext(Dispatchers.IO) {
        val vault = KeystoreVault(loadKey = { SecretKeySpec(ByteArray(32), "AES") })
        val sealed = vault.seal("test SSH key".toByteArray(), KeyProtection.PLAIN)
        for (failure in listOf(KeyPermanentlyInvalidatedException(), java.security.UnrecoverableKeyException().apply {
            initCause(KeyPermanentlyInvalidatedException())
        })) {
            vault.authorizer = BiometricAuthorizer { throw failure }
            val error = runCatching { vault.open(sealed, KeyProtection.BIOMETRIC) }.exceptionOrNull()
            assertTrue("$error", error is VaultException.Invalidated)
        }
        vault.authorizer = BiometricAuthorizer { null }
        assertTrue(runCatching { vault.open(sealed, KeyProtection.BIOMETRIC) }.exceptionOrNull() is VaultException.Locked)
    } }

    @Test fun otherKeysStillOfferRecoveryAfterTheWrappingKeyHasRotated() {
        var wrappingKey = SecretKeySpec(ByteArray(32), "AES")
        val vault = KeystoreVault(loadKey = { wrappingKey })
        val original = vault.seal("old private key".toByteArray(), KeyProtection.PLAIN)
        wrappingKey = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val replacement = vault.seal("recovered private key".toByteArray(), KeyProtection.PLAIN)
        assertTrue(runCatching { vault.open(original, KeyProtection.PLAIN) }.exceptionOrNull() is VaultException.Invalidated)
        assertArrayEquals("recovered private key".toByteArray(), vault.open(replacement, KeyProtection.PLAIN))
    }

}
