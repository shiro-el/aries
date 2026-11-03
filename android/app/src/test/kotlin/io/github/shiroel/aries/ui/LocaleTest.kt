package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The UI follows the device language; languages without a translation fall back to English. */
@RunWith(RobolectricTestRunner::class)
class LocaleTest {
    @get:Rule val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(FakeCore(), scope) }
            MaterialTheme { AriesApp(model) }
        }
    }

    @Test
    @Config(qualifiers = "ko-rKR")
    fun koreanDeviceShowsKorean() {
        show()
        compose.onNodeWithText("Host").assertExists()
        compose.openKeyDetails()
        compose.onNodeWithText("SSH 키 만들기").assertExists()
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishDeviceShowsEnglish() {
        show()
        compose.onNodeWithText("Hosts").assertExists()
        compose.openKeyDetails()
        compose.onNodeWithText("Create SSH key").assertExists()
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun untranslatedLanguageFallsBackToEnglish() {
        show()
        compose.onNodeWithText("Hosts").assertExists()
        compose.openKeyDetails()
        compose.onNodeWithText("Create SSH key").assertExists()
    }
}
