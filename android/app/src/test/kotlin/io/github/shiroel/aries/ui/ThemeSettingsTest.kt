package io.github.shiroel.aries.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Appearance preferences through Android storage, including a fresh settings owner. */
@RunWith(RobolectricTestRunner::class)
class ThemeSettingsTest {
    @Test
    fun invalidHexNeverChangesOrPersistsAnAccent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("invalid-theme-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = ThemeSettings(preferences)
        assertTrue(settings.setAccent("E0668F"))
        for (invalid in listOf("", "#123", "#1234567", "#GG1234", "#FF123456", "##123456", " 123456")) {
            assertFalse(invalid, settings.setAccent(invalid))
            assertEquals("#E0668F", settings.accentHex)
            assertEquals("#E0668F", ThemeSettings(preferences).accentHex)
        }
    }

    @Test
    fun defaultsToSystemAndSaffronAndRestoresTheUsersAppearance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("theme-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = ThemeSettings(preferences)
        assertEquals(AppLayout.CONSOLE, settings.layout)
        assertEquals(ThemeMode.SYSTEM, settings.mode)
        assertEquals("#F4B73A", settings.accentHex)

        settings.chooseMode(ThemeMode.DARK)
        assertTrue(settings.setAccent("#3a9fd8"))

        // Regression: persisting a layout must not overwrite mode/accent (or vice versa).
        for (layout in AppLayout.entries) {
            settings.chooseLayout(layout)
            val restored = ThemeSettings(preferences)
            assertEquals(layout, restored.layout)
            assertEquals(ThemeMode.DARK, restored.mode)
            assertEquals("#3A9FD8", restored.accentHex)
        }
        settings.chooseLayout(AppLayout.THREAD)
        settings.chooseMode(ThemeMode.LIGHT)
        settings.setAccent("#E0668F")
        assertEquals(AppLayout.THREAD, ThemeSettings(preferences).layout)
        preferences.edit().putString("layout", "removed-layout").commit()
        assertEquals(AppLayout.CONSOLE, ThemeSettings(preferences).layout)
    }
}
