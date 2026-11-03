package io.github.shiroel.aries

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shiroel.aries.dictation.DictationStore
import java.io.File
import java.security.KeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictationKeyDeviceTest {
    @Test fun keySurvivesRelaunchSealedInNoBackupStorageAndRemovalHidesDictation() {
        // Instrumentation uses the app UID. Isolate files inside its writable no-backup directory.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.noBackupFilesDir, "dictation-device-test").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(target) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                target.getSharedPreferences("dictation-device-test-$name", mode)
        }
        val alias = "aries-groq-device-test"
        val vault = KeystoreVault(alias)
        val store = DictationStore(context, vault)
        try {
            store.clearKey()
            assertFalse(store.hasKey)
            store.saveKey("gsk-device-test-secret")
            val again = DictationStore(context, vault)
            assertEquals("gsk-device-test-secret", again.readKey())
            assertTrue(again.hasKey)
            val sealed = File(context.noBackupFilesDir, "groq-key.sealed")
            assertTrue(sealed.isFile)
            assertFalse(sealed.readText().contains("gsk-device-test-secret"))
            sealed.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(again.hasKey)
            store.clearKey()
            assertNull(again.readKey())
            assertFalse(sealed.exists())
        } finally {
            store.clearKey()
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
