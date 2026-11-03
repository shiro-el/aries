package io.github.shiroel.aries

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shiroel.aries.core.AriesCore
import io.github.shiroel.aries.core.VaultException
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun sealsAndOpens() {
        val vault = KeystoreVault(alias)
        val secret = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray()
        val sealed = vault.seal(secret)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("OPENSSH"))
        assertArrayEquals(secret, vault.open(sealed))
    }

    @Test
    fun rejectsTamperedData() {
        val vault = KeystoreVault(alias)
        val sealed = vault.seal(byteArrayOf(1, 2, 3))
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertThrows(VaultException::class.java) { vault.open(sealed) }
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
}
