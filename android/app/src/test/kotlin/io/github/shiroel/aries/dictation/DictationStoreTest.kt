package io.github.shiroel.aries.dictation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.KeyVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DictationStoreTest {
    private val vault = object : KeyVault {
        override fun seal(plaintext: ByteArray, protection: KeyProtection) = plaintext.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
        override fun open(sealed: ByteArray, protection: KeyProtection) = seal(sealed, protection)
    }
    @Test fun savesOnlySealedKeyOutsideBackupsAndCanRemoveIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DictationStore(context, vault)
        store.clearKey()
        assertFalse(store.hasKey)
        store.saveKey("gsk-test-secret")
        val again = DictationStore(context, vault)
        assertTrue(again.hasKey)
        assertEquals("gsk-test-secret", again.readKey())
        val file = java.io.File(context.noBackupFilesDir, "groq-key.sealed")
        assertTrue(file.isFile)
        assertFalse(file.readText().contains("gsk-test-secret"))
        store.saveOptions(DictationOptions("ko", DictationModel.TURBO, "Aries", true))
        assertEquals(DictationOptions("ko", DictationModel.TURBO, "Aries", true), again.options())
        store.clearKey()
        assertNull(again.readKey())
        assertFalse(again.hasKey)
    }
}
