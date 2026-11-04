package io.github.shiroel.aries.ui

import android.os.Bundle
import android.os.LocaleList
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.LocaleManagerCompat
import io.github.shiroel.aries.AppLanguage
import io.github.shiroel.aries.HomeWidgets
import io.github.shiroel.aries.PushService
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class LanguageSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Aries)
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(FakeCore(), scope) }
            AriesTheme(ThemeSettings.from(this)) {
                SettingsScreen(model, ThemeSettings.from(this), onBack = {})
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "ko-rKR-h1200dp")
class AppLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun languageSelectionAppliesAfterRecreationAndCanReturnToSystem() {
        val activity = Robolectric.buildActivity(LanguageSettingsActivity::class.java).setup()
        try {
            compose.onNodeWithTag("app-language").performClick()
            compose.onNodeWithTag("app-language-search").performTextInput("English")
            compose.onNodeWithTag("app-language:en").performClick()
            compose.waitForIdle()
            activity.recreate()
            assertEquals("Settings", activity.get().getString(R.string.settings_title))
            compose.onNodeWithText("Settings").assertExists()
            assertBackgroundLanguage(activity.get().applicationContext, "Push works for box", "Needs you · 0", "No saved Agents")
            compose.waitUntil(5_000) {
                LocaleManagerCompat.getApplicationLocales(activity.get()).toLanguageTags() == "en"
            }
            compose.onNodeWithTag("app-language").performClick()
            compose.onNodeWithTag("app-language-search").performTextInput("한국")
            compose.onNodeWithTag("app-language:ko").performClick()
            compose.waitForIdle()
            activity.recreate()
            assertEquals("설정", activity.get().getString(R.string.settings_title))
            compose.onNodeWithText("설정").assertExists()
            assertBackgroundLanguage(activity.get().applicationContext, "box 푸시 알림 테스트", "확인 필요 · 0", "저장된 Agent 없음")
            compose.onNodeWithTag("app-language").performClick()
            compose.onNodeWithTag("app-language:").performClick()
            compose.waitForIdle()
            activity.recreate()
            assertEquals("설정", activity.get().getString(R.string.settings_title))
            compose.onNodeWithText("설정").assertExists()
            assertBackgroundLanguage(activity.get().applicationContext, "box 푸시 알림 테스트", "확인 필요 · 0", "저장된 Agent 없음")
            assertEquals("", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        } finally {
            AppLanguage.choose(activity.get(), "")
            activity.pause().stop().destroy()
        }
    }

    @Test @Config(sdk = [33])
    fun pickerUsesAndroidsLocaleSettingAndReflectsChangesMadeInSystemSettings() {
        val activity = Robolectric.buildActivity(LanguageSettingsActivity::class.java).setup()
        val manager = activity.get().getSystemService(LocaleManager::class.java)
        try {
            compose.onNodeWithTag("app-language").performClick()
            compose.onNodeWithTag("app-language:en").performClick()
            compose.waitForIdle()
            activity.recreate()
            assertEquals("en", manager.applicationLocales.toLanguageTags())
            compose.onNodeWithTag("app-language").assertTextContains("English")

            manager.applicationLocales = LocaleList.forLanguageTags("ko")
            activity.recreate()
            compose.onNodeWithTag("app-language").assertTextContains("한국어")
            compose.onNodeWithTag("app-language").performClick()
            compose.onNodeWithTag("app-language:").performClick()
            compose.waitForIdle()
            assertEquals("", manager.applicationLocales.toLanguageTags())
        } finally {
            AppLanguage.choose(activity.get(), "")
            activity.pause().stop().destroy()
        }
    }

    private fun assertBackgroundLanguage(context: Context, title: String, count: String, empty: String) {
        PushService.show(context, PushNotice("h1", "box", PushContent.Test(1uL)))
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val widget = HomeWidgets.render(context, emptyList(), emptyList(), expanded = true)
            .apply(context, FrameLayout(context))
        assertEquals(count, widget.findViewById<TextView>(R.id.widget_count).text.toString())
        assertEquals(empty, widget.findViewById<TextView>(R.id.widget_empty).text.toString())
    }
}
