package io.github.shiroel.aries.wear

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchSummary
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchInboxTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun returningFromAnAgentRestoresTheInboxScrollPosition() {
        val rows = (1..24).map { WatchAgent("host", "t$it", "Agent $it", "blocked", hostName = "Studio") }
        val summary = WatchSummary(24, rows.take(3), 60_000, inbox = rows)
        compose.setContent {
            var target by remember { mutableStateOf<Pair<String, String>?>(null) }
            WatchInbox(summary, 180_000, target, { target = it }, false, null, {}, {}, detailActions = {})
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Agent 20"))
        screenshot("inbox-scrolled")
        compose.onNodeWithText("Agent 20").performClick()
        screenshot("inbox-detail")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.wear_inbox_back)))
        compose.onNodeWithText(context.getString(R.string.wear_inbox_back)).performClick()
        compose.onNodeWithText("Agent 20").assertIsDisplayed()
        screenshot("inbox-returned")
    }

    private fun screenshot(name: String) {
        System.getenv("ARIES_FRESHNESS_SHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
