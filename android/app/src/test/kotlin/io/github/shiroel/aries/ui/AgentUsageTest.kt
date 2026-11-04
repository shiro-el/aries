package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.TerminalInfo
import java.io.File
import org.robolectric.annotation.GraphicsMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentUsage
import io.github.shiroel.aries.core.ContextUsage
import io.github.shiroel.aries.core.UsageLimit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression: unavailable measurements must not render as 0%/100% remaining,
 * while a recorded zero quota is shown as zero after a live update. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentUsageTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** Regression: removing the fixed usage row must retain a working route to its sheet. */
    @Test fun moreMenuOpensTheUsageSheet() {
        compose.setContent { MaterialTheme {
            AgentScreenHeader(TerminalInfo("t1", "codex", "", listOf("codex"), "/repo", "live", AgentStatus.Working),
                "home", {}, AgentView.CHAT, {})
        } }
        compose.onNodeWithTag("agent-usage-sheet").assertDoesNotExist()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(str(R.string.usage_title), substring = true).performClick()
        compose.onNodeWithTag("agent-usage-sheet").assertExists()
        compose.onNodeWithText(str(R.string.usage_limits_unavailable)).assertIsDisplayed()
    }

    @Test fun unknown_and_measured_zero_remain_distinct_as_readings_change() {
        val reading = mutableStateOf<AgentUsage?>(null)
        compose.setContent { MaterialTheme {
            Column(Modifier.testTag("usage-preview")) {
                AgentScreenHeader(TerminalInfo("t1", "codex", "", listOf("codex"), "/repo", "live", AgentStatus.Working),
                    "home", {}, AgentView.CHAT, {}, usage = reading.value)
            }
        } }
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(str(R.string.usage_context_unavailable), substring = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.usage_title), substring = true).performClick()
        compose.onNodeWithText(str(R.string.usage_limits_unavailable)).assertIsDisplayed()
        screenshot("unavailable", "agent-usage-sheet")
        compose.runOnIdle {
            reading.value = AgentUsage(ContextUsage(50000uL, 200000uL, "2026-11-04T10:00:00Z"), listOf(
                UsageLimit("primary", 300uL, 0u, 1793790000uL, "2026-11-04T10:00:00Z"),
            ))
        }
        compose.onNodeWithText(str(R.string.usage_used, "0")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.usage_context_tokens, "50,000", "200,000")).assertIsDisplayed()
        screenshot("limits", "agent-usage-sheet")
        compose.onNodeWithText(str(R.string.usage_close)).performClick()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(str(R.string.usage_context_remaining, "75"), substring = true).assertIsDisplayed()
        screenshot("header", "usage-preview")
        compose.runOnIdle { reading.value = null }
        compose.onNodeWithText(str(R.string.usage_context_unavailable), substring = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.usage_title), substring = true).performClick()
        compose.onNodeWithText(str(R.string.usage_limits_unavailable)).assertIsDisplayed()
    }
    private fun screenshot(name: String, tag: String) {
        System.getenv("ARIES_USAGE_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
