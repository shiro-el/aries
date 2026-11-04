package io.github.shiroel.aries.wear

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.weardata.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "h1200dp")
class WatchDraftFlowTest {
    @get:Rule val compose = createComposeRule()

    // D77: failed dictated text must survive Back, detail reentry and a live recovery check.
    @Test fun failedVoiceReplySurvivesLeavingDetailWithoutSendingAgain() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val row = WatchAgent("host", "t1", "Agent", "done", stateToken = "turn1")
        var sends = 0
        val session = WatchDetailSession(scope, { path, bytes ->
            if (path == WatchCommand.DETAILS_PATH) WatchCommand(row).toBytes()
            else { sends++; assertEquals("Run the full checks tomorrow", WatchCommand.fromBytes(bytes)?.text); WatchResult.FAILED.toBytes() }
        })
        var showing by mutableStateOf(true)
        session.merge(WatchSummary(0, emptyList(), 1, inbox = listOf(row)))
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?) {
                val result = android.content.Intent()
                android.app.RemoteInput.addResultsToIntent(arrayOf(android.app.RemoteInput.Builder("watch_reply").build()), result,
                    android.os.Bundle().apply { putCharSequence("watch_reply", "Run the full checks tomorrow") })
                dispatchResult(requestCode, android.app.Activity.RESULT_OK, result)
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                WatchConsoleTheme { if (showing) WatchAnswerActions(row, session) }
            }
        }
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        compose.runOnIdle { showing = false }
        compose.runOnIdle { showing = true }
        compose.onNodeWithText("Run the full checks tomorrow").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Run the full checks tomorrow").assertExists()
        compose.onNodeWithText("Send").assertHasClickAction()
        assertEquals(1, sends)
        scope.cancel()
    }
}
