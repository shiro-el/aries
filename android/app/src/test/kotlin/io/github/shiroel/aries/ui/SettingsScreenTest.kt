package io.github.shiroel.aries.ui

import android.app.TimePickerDialog
import android.content.DialogInterface
import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.QuietHours
import io.github.shiroel.aries.QuietHoursSettings
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AttachMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Settings as reached from Hosts, with real appearance persistence and the core's existing settings. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun settingsPersistQuietHoursAndAllowChangingBothTimes() {
        QuietHoursSettings.from(context).update(QuietHours())
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(FakeCore(), scope) }
            AriesApp(model)
        }
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("quiet-hours")
        compose.onNodeWithTag("quiet-hours-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("quiet-hours-start").performScrollTo().performClick()
        compose.runOnIdle {
            val picker = ShadowDialog.getLatestDialog() as TimePickerDialog
            picker.updateTime(21, 30)
            picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        compose.onNodeWithTag("quiet-hours-end").performClick()
        compose.runOnIdle {
            val picker = ShadowDialog.getLatestDialog() as TimePickerDialog
            picker.updateTime(6, 15)
            picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            assertEquals(QuietHours(true, 1290, 375), QuietHoursSettings.from(context).hours)
        }
        compose.onNodeWithTag("quiet-hours-enabled").performClick()
        compose.runOnIdle {
            assertEquals(QuietHours(false, 1290, 375), QuietHoursSettings.from(context).hours)
        }
    }

    @Test
    fun settingsEditsAppearanceAndKeepsCacheAndAttachControlsReachable() {
        val preferences = context.getSharedPreferences("settings-screen-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = ThemeSettings(preferences)
        val core = FakeCore()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model, settings)
        }

        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNodeWithText(str(R.string.theme_dark)).performClick()
        compose.onNodeWithContentDescription(str(R.string.accent_iris)).performClick()
        compose.runOnIdle {
            assertEquals(ThemeMode.DARK, ThemeSettings(preferences).mode)
            assertEquals("#8C7CF0", ThemeSettings(preferences).accentHex)
        }

        compose.onNodeWithTag("accent-custom").performClick()
        compose.onNodeWithTag("accent-hex").performTextReplacement("#12zz00")
        compose.onNodeWithText(str(R.string.accent_hex_invalid)).assertExists()
        compose.runOnIdle { assertEquals("#8C7CF0", ThemeSettings(preferences).accentHex) }
        compose.onNodeWithTag("accent-hex").performTextReplacement("#804020")
        compose.onNodeWithText(str(R.string.accent_hex_invalid)).assertDoesNotExist()
        compose.runOnIdle { assertEquals("#804020", ThemeSettings(preferences).accentHex) }

        compose.onNodeWithContentDescription(str(R.string.accent_hue)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(180f) }
        compose.runOnIdle { assertEquals("#208080", ThemeSettings(preferences).accentHex) }

        compose.onNodeWithText(str(R.string.ok_button)).performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(str(R.string.chat_cache_days, 7)))
        compose.onNodeWithText(str(R.string.chat_cache_days, 7)).performClick()
        compose.runOnIdle { assertEquals(7u, core.retentionDays) }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("attach-mode")
        compose.onNodeWithText(str(R.string.attach_input_label)).performClick()
        compose.runOnIdle { assertEquals(AttachMode.INPUT, core.attachMode()) }

        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.onNodeWithText(str(R.string.hosts_title)).assertExists()
    }
    // Regression: lowering saturation/value to zero must not discard the custom hue when raised again.
    @Test fun customAccentPreservesHueThroughGreyAndBlack() {
        val prefs = context.getSharedPreferences("settings-hsv-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = ThemeSettings(prefs).apply { setAccent("#00AAFF") }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(FakeCore(), scope) }
            AriesApp(model, settings)
        }
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNodeWithTag("accent-custom").performClick()
        fun set(label: Int, value: Float) {
            compose.onNodeWithContentDescription(str(label)).performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        }
        set(R.string.accent_saturation, 0f)
        compose.runOnIdle { assertEquals("#FFFFFF", settings.accentHex) }
        set(R.string.accent_saturation, 1f)
        compose.runOnIdle { assertEquals("#00AAFF", settings.accentHex) }
        set(R.string.accent_brightness, 0f)
        compose.runOnIdle { assertEquals("#000000", settings.accentHex) }
        set(R.string.accent_brightness, 1f)
        compose.runOnIdle { assertEquals("#00AAFF", settings.accentHex) }
    }

}
