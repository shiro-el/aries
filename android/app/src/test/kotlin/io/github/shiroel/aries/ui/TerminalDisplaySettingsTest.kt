package io.github.shiroel.aries.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalDisplaySettingsTest {
    @Test
    fun displayPreferencesSurviveANewSettingsOwner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("terminal-display-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = TerminalDisplaySettings(preferences)
        assertEquals(14, settings.fontSize)
        assertEquals(TerminalFont.SYSTEM, settings.font)
        assertFalse(settings.minimumContrast)

        settings.chooseFontSize(20)
        settings.chooseFont(TerminalFont.JETBRAINS_MONO)
        settings.chooseMinimumContrast(true)
        val restored = TerminalDisplaySettings(preferences)
        assertEquals(20, restored.fontSize)
        assertEquals(TerminalFont.JETBRAINS_MONO, restored.font)
        assertTrue(restored.minimumContrast)
    }
}
