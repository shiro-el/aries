package io.github.shiroel.aries

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import io.github.shiroel.aries.ui.FakeCore
import io.github.shiroel.aries.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ChatConfigurationChangeApplication : AriesApplication() {
    override val core = FakeCore().apply {
        addHost("rotation-host", Endpoint.Ssh("home.test", 22u, "me"))
        savedView = AgentView.CHAT
        terminals = listOf(TerminalInfo("t1", "rotation-terminal", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
    }
}

/** Real Activity/ViewModel lifetime, using the fake core's Chat watch. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ChatConfigurationChangeApplication::class, qualifiers = "h800dp")
class ChatConfigurationChangeTest {
    @get:Rule val compose = createEmptyComposeRule()

    // D125: the retained stop decision must remain visible after actual Activity recreation.
    @Test fun interruptConfirmationSurvivesRotation() {
        val app = ApplicationProvider.getApplicationContext<ChatConfigurationChangeApplication>()
        app.getSharedPreferences("app-guidance", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            compose.onNodeWithText("rotation-host").performClick()
            compose.onNodeWithText("rotation-terminal").performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.chat_interrupt_confirm_button)).performClick()
            activity.recreate()
            compose.waitForIdle()
            compose.onNodeWithText(app.getString(R.string.chat_interrupt_title)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.cancel_button)).performClick()
            assertEquals(emptyList<String>(), app.core.connection!!.interrupted)
        } finally { activity.pause().stop().destroy() }
    }

    // Regression: retaining the open Chat's viewport across Activity recreation must not
    // also restore that viewport when the same cached conversation is explicitly reopened.
    // A Terminal toggle is part of the retained visit, including its pixel offset/count.
    @Test fun chatKeepsItsReadingPositionOnRotationButReopensAtLatest() {
        val app = ApplicationProvider.getApplicationContext<ChatConfigurationChangeApplication>()
        app.getSharedPreferences("app-guidance", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val model = ViewModelProvider(activity.get())[HostsViewModel::class.java].model
            compose.waitUntil(5_000) { compose.onAllNodesWithText("rotation-host").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("rotation-host").performClick()
            compose.onNodeWithText("rotation-terminal").performClick()
            compose.waitUntil(5_000) { model.screen is Screen.Chat }
            compose.waitForIdle()
            fun push(count: Int) {
                app.core.pushChat(ChatSnapshot((1..count).map {
                    ChatEntry("reply$it", null,
                        ChatEntryBody.Agent("Rotation reply $it\nSecond line\nThird line"))
                }, ChatStatus.LIVE, null))
                compose.waitForIdle()
                compose.waitUntil(5_000) {
                    (model.screen as Screen.Chat).chat.snapshot?.entries?.size == count
                }
                compose.waitForIdle()
            }
            push(40)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Rotation reply 40\nSecond line\nThird line").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Rotation reply 6\nSecond line\nThird line"))
            compose.onNodeWithTag("chat-transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 25f) }
            push(42)
            val reply = "Rotation reply 6\nSecond line\nThird line"
            val before = compose.onNodeWithText(reply).fetchSemanticsNode().boundsInRoot.top
            val chat = (model.screen as Screen.Chat).chat

            activity.recreate()
            compose.waitForIdle()

            compose.onNodeWithText(reply).assertIsDisplayed()
            assertEquals(before, compose.onNodeWithText(reply).fetchSemanticsNode().boundsInRoot.top, 0.5f)
            compose.onNodeWithContentDescription("Latest (2)").assertIsDisplayed()
            compose.runOnIdle { model.switchView(AgentView.TERMINAL) }
            compose.waitForIdle()
            compose.runOnIdle { model.switchView(AgentView.CHAT) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(reply).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, compose.onNodeWithText(reply).fetchSemanticsNode().boundsInRoot.top, 0.5f)
            compose.onNodeWithContentDescription("Latest (2)").assertIsDisplayed()
            compose.runOnIdle { model.closeChat() }
            compose.waitForIdle()
            compose.onNodeWithText("rotation-terminal").performClick()
            compose.waitUntil(5_000) { model.screen is Screen.Chat }
            compose.waitForIdle()
            assertSame("draft/answer owner is reused across visits", chat, (model.screen as Screen.Chat).chat)
            push(43)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Rotation reply 43\nSecond line\nThird line").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Rotation reply 43\nSecond line\nThird line").assertIsDisplayed()
            compose.onNodeWithContentDescription("Latest").assertDoesNotExist()
            compose.onNodeWithContentDescription("Latest (3)").assertDoesNotExist()
            push(44)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Rotation reply 44\nSecond line\nThird line").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Rotation reply 44\nSecond line\nThird line").assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
