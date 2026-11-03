package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: settings reached through the real app, with device-local storage. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class TerminalDisplayUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun userCanSaveTerminalDisplayPreferencesAndReadTheBundledFontLicense() {
        context.getSharedPreferences("terminal_display", Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(FakeCore(), scope) }
            AriesApp(model)
        }
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("terminal-display")
        compose.onNodeWithText(context.getString(R.string.terminal_display_font_jetbrains)).performClick()
        compose.onNodeWithTag("terminal-font-size").performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        compose.onNodeWithText(context.getString(R.string.terminal_display_contrast_label)).performClick()
        compose.runOnIdle {
            val restored = TerminalDisplaySettings.from(context)
            assertEquals(TerminalFont.JETBRAINS_MONO, restored.font)
            assertEquals(20, restored.fontSize)
            assertTrue(restored.minimumContrast)
        }
        compose.onNodeWithText(context.getString(R.string.terminal_display_license_button)).performClick()
        compose.onNodeWithText("Copyright 2020 The JetBrains Mono Project Authors", substring = true).assertExists()
        compose.onNodeWithText("SIL OPEN FONT LICENSE Version 1.1", substring = true).assertExists()
    }
}
