package io.github.shiroel.aries.ui

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
@Config(qualifiers = "h1200dp")
class AgentUsageTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test fun unknown_and_measured_zero_remain_distinct_as_readings_change() {
        val reading = mutableStateOf<AgentUsage?>(null)
        compose.setContent { MaterialTheme { AgentUsageHeader(reading.value) } }
        compose.onNodeWithText(str(R.string.usage_context_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.usage_title)).performClick()
        compose.onNodeWithText(str(R.string.usage_limits_unavailable)).assertIsDisplayed()
        compose.runOnIdle {
            reading.value = AgentUsage(ContextUsage(50000uL, 200000uL, "2026-11-04T10:00:00Z"), listOf(
                UsageLimit("primary", 300uL, 0u, 1793790000uL, "2026-11-04T10:00:00Z"),
            ))
        }
        compose.onNodeWithText(str(R.string.usage_used, "0")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.usage_context_remaining, "75")).assertIsDisplayed()
        compose.runOnIdle { reading.value = null }
        compose.onNodeWithText(str(R.string.usage_context_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.usage_limits_unavailable)).assertIsDisplayed()
    }
}
