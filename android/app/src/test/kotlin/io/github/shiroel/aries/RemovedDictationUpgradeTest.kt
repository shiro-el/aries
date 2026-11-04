package io.github.shiroel.aries

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.ui.FakeCore
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seed an old installation before the real Application startup migration runs. */
class RemovedDictationUpgradeApplication : AriesApplication() {
    override val core: AriesCoreInterface = FakeCore()

    override fun onCreate() {
        for (suffix in listOf("", ".bak", ".new")) {
            File(noBackupFilesDir, "groq-key.sealed$suffix").writeText("legacy-sealed-test-key")
        }
        check(getSharedPreferences("dictation", Context.MODE_PRIVATE).edit()
            .putString("language", "ko").putString("vocabulary", "private project name").commit())
        super.onCreate()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = RemovedDictationUpgradeApplication::class)
class RemovedDictationUpgradeTest {
    @Test fun startupErasesPreviouslyStoredKeyAndPreferences() {
        val app = ApplicationProvider.getApplicationContext<RemovedDictationUpgradeApplication>()
        for (suffix in listOf("", ".bak", ".new")) {
            assertFalse("legacy key file remains: $suffix", File(app.noBackupFilesDir, "groq-key.sealed$suffix").exists())
        }
        assertTrue(app.getSharedPreferences("dictation", Context.MODE_PRIVATE).all.isEmpty())
        assertFalse(File(app.applicationInfo.dataDir, "shared_prefs/dictation.xml").exists())
    }
}
