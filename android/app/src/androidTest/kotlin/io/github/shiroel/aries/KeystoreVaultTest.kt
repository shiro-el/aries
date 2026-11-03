package io.github.shiroel.aries

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.KeyVault
import io.github.shiroel.aries.core.VaultException
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** On a device: the SSH key is sealed by the Android Keystore through the real core. */
@RunWith(AndroidJUnit4::class)
class KeystoreVaultTest {
    private val alias = "aries-vault-test"
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "vault-test")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.apply { deleteEntry(alias); deleteEntry("$alias-biometric") }
    }

    @Test
    fun sealsAndOpens() {
        val vault = KeystoreVault(alias)
        val secret = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray()
        val sealed = vault.seal(secret, KeyProtection.PLAIN)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("OPENSSH"))
        assertArrayEquals(secret, vault.open(sealed, KeyProtection.PLAIN))
    }

    @Test
    fun rejectsTamperedData() {
        val vault = KeystoreVault(alias)
        val sealed = vault.seal(byteArrayOf(1, 2, 3), KeyProtection.PLAIN)
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertThrows(VaultException::class.java) { vault.open(sealed, KeyProtection.PLAIN) }
    }

    @Test
    fun coreKeepsTheKeyAcrossRelaunchesWithoutPlaintextOnDisk() {
        val first = AriesCore(dir.absolutePath, KeystoreVault(alias)).ensureKey()
        val again = AriesCore(dir.absolutePath, KeystoreVault(alias)).publicKey()
        assertEquals(first, again)
        dir.listFiles()!!.forEach { f ->
            assertFalse(f.name, String(f.readBytes(), Charsets.ISO_8859_1).contains("PRIVATE KEY"))
        }
    }

    @Test
    fun importedKeyIsSealedByAndroidKeystoreAndOpensAfterRelaunch() = runBlocking {
        val vault = KeystoreVault(alias)
        var privateKey = byteArrayOf()
        // Capture a disposable generated key at the public vault seam, in memory.
        val source = AriesCore(dir.absolutePath, object : KeyVault {
            override fun seal(plaintext: ByteArray, protection: KeyProtection): ByteArray {
                privateKey = plaintext.copyOf()
                return vault.seal(plaintext, protection)
            }
            override fun open(sealed: ByteArray, protection: KeyProtection) = vault.open(sealed, protection)
        })
        val public = source.ensureKey()
        val core = AriesCore(dir.absolutePath, vault)
        val imported = core.importKey("existing", String(privateKey, Charsets.UTF_8), "")
        privateKey.fill(0)
        assertEquals(public.openssh, imported.openssh)
        val host = core.addHost("closed", io.github.shiroel.aries.core.Endpoint.Ssh("127.0.0.1", 1u, "aries"))
        core.setHostSshKey(host.id, imported.id)

        val again = AriesCore(dir.absolutePath, KeystoreVault(alias))
        assertEquals(imported, again.keys().last())
        try {
            again.connect(host.id)
            fail("nothing listens on port 1")
        } catch (e: CoreException) {
            // Reaching SSH proves the sealed imported key could be opened.
            assertTrue(e is CoreException.Unreachable)
        }
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            assertFalse(file.name, String(file.readBytes(), Charsets.ISO_8859_1).contains("PRIVATE KEY"))
        }
    }

    @Test
    fun plainKeyNeedsNoUserEvenWithoutAnAuthorizer() {
        val vault = KeystoreVault(alias) // no Activity installed a prompt
        val sealed = vault.seal(byteArrayOf(4, 5, 6), KeyProtection.PLAIN)
        assertArrayEquals(byteArrayOf(4, 5, 6), vault.open(sealed, KeyProtection.PLAIN))
    }

    @Test
    fun biometricKeyIsNeverUsedQuietlyInTheBackground() {
        // No authorizer = no Activity in the foreground. On a phone with a
        // biometric this is Locked; on a device without one (this emulator)
        // the key cannot even be made. Either way it fails, at once.
        val vault = KeystoreVault(alias)
        val started = System.nanoTime()
        try {
            vault.seal(byteArrayOf(1), KeyProtection.BIOMETRIC)
            fail("a biometric key must not work without the user")
        } catch (e: VaultException) {
            assertTrue(e.toString(), e is VaultException.Locked || e is VaultException.BiometricUnavailable)
        }
        assertTrue("must fail fast", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun coreKeepsThePlainKeyWhenBiometricCannotBeSetUp() {
        val core = AriesCore(dir.absolutePath, KeystoreVault(alias))
        val key = core.ensureKey()
        try {
            core.setKeyProtection(KeyProtection.BIOMETRIC)
            // Only possible with an enrolled biometric and a user present.
            fail("no authorizer installed, so biometric must not be set")
        } catch (e: CoreException) {
            assertTrue(e.toString(), e is CoreException.KeyLocked || e is CoreException.BiometricUnavailable)
        }
        val again = AriesCore(dir.absolutePath, KeystoreVault(alias))
        assertEquals(KeyProtection.PLAIN, again.keyProtection())
        assertEquals(key, again.publicKey())
    }
}
