package io.github.shiroel.aries.wear

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import org.robolectric.annotation.GraphicsMode
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
@Config(sdk = [33], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchDetailFlowTest {
    @get:Rule val compose = createComposeRule()
    private val row = WatchAgent("host", "t1", "Agent", "blocked", stateToken = "turn1", requestKind = "permission")
    private fun summary(agent: WatchAgent) = WatchSummary(1, listOf(agent), 1, inbox = listOf(agent))

    // Catches wake/resume and cached DataItem delivery removing live Allow/Deny,
    // plus stale permission menus surviving a privacy or turn change.
    @Test fun livePermissionSurvivesRepublishingButNotPrivacyOrANewTurn() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var live = row.copy(requestId = "permission:edit", choices = listOf("Allow", "Deny"), canAnswerOnWatch = true)
        val session = WatchDetailSession(scope, { _, _ -> WatchCommand(live).toBytes() })
        compose.setContent { WatchInbox(session.summary, 1, row.hostId to row.terminal, {}, false, null, {}, {}, detailActions = { WatchAnswerActions(it, session) }) }
        compose.runOnIdle { session.merge(summary(row)); session.load(row) }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Deny"))
        compose.onNodeWithText("Deny").assertExists()
        screenshot("permission-actions")
        compose.runOnIdle { session.merge(summary(row)) }
        compose.onNodeWithText("Deny").assertExists()
        // A refreshed live permission must also replace the old live override.
        compose.runOnIdle {
            live = live.copy(requestId = "permission:second-edit")
            session.recover(session.summary.inbox.single())
        }
        compose.waitForIdle()
        compose.runOnIdle { session.merge(summary(row)) }
        assertEquals("permission:second-edit", session.summary.inbox.single().requestId)
        compose.runOnIdle { session.merge(summary(row.copy(contentHidden = true))) }
        compose.onNodeWithText("Deny").assertDoesNotExist()
        compose.runOnIdle { session.merge(summary(row.copy(stateToken = "turn2"))) }
        compose.onNodeWithText("Deny").assertDoesNotExist()
        scope.cancel()
    }

    // Catches duplicate sends while an old turn is pending, and a late outcome
    // hiding the next question after leaving and reopening detail.
    @Test fun onlyPendingAnswerSurvivesAReplacementRequestAndDetailReentry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reply = CompletableDeferred<ByteArray>()
        val question = row.copy(requestKind = "question", requestId = "q1", choices = listOf("Blue"), canAnswerOnWatch = true)
        val session = WatchDetailSession(scope, { path, _ ->
            if (path == WatchCommand.DETAILS_PATH) WatchCommand(question).toBytes() else reply.await()
        })
        var showing by mutableStateOf(true)
        compose.runOnIdle { session.merge(summary(question)); session.load(question) }
        compose.setContent { if (showing) WatchInbox(session.summary, 1, row.hostId to row.terminal, {}, false, null, {}, {}, detailActions = { WatchAnswerActions(it, session) }) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Blue"))
        compose.onNodeWithText("Blue").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sending…"))
        compose.onNodeWithText("Sending…").assertExists()
        screenshot("answer-sending")
        compose.runOnIdle { showing = false; session.merge(summary(question.copy(requestId = "q2", choices = listOf("Green")))) }
        compose.runOnIdle { showing = true }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sending…"))
        compose.onNodeWithText("Sending…").assertExists()
        compose.onNodeWithText("Blue").assertDoesNotExist()
        compose.runOnIdle { reply.complete(WatchResult.SENT.toBytes()) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Green"))
        compose.onNodeWithText("Sent").assertDoesNotExist()
        compose.onNodeWithText("Green").assertHasClickAction().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sent"))
        compose.onNodeWithText("Sent").assertExists()
        screenshot("answer-sent-for-current-turn")
        compose.runOnIdle { showing = false }
        compose.runOnIdle { session.merge(session.summary); showing = true }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sent"))
        compose.onNodeWithText("Sent").assertExists()
        compose.onNodeWithText("Blue").assertDoesNotExist()
        // A different request with the same terminal metadata must replace the live menu.
        compose.runOnIdle { session.merge(summary(question.copy(requestId = "q3", choices = listOf("Red")))) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Red"))
        compose.onNodeWithText("Sent").assertDoesNotExist()
        compose.onNodeWithText("Red").assertHasClickAction()
        // Even a new token with the same request ID must have fresh actions.
        compose.runOnIdle { session.merge(summary(question.copy(stateToken = "turn3", requestId = "q2"))) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Blue"))
        compose.onNodeWithText("Sent").assertDoesNotExist()
        compose.onNodeWithText("Blue").assertHasClickAction()
        screenshot("answer-next-question")
        scope.cancel()
    }

    // Catches Stop being cancelled by swiping out of detail before its reply.
    @Test @Config(qualifiers = "h1200dp") fun leavingDetailStillReceivesStopOutcome() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reply = CompletableDeferred<ByteArray>()
        var completed: WatchInterruptResult? = null
        val session = WatchDetailSession(scope, { _, _ -> reply.await() }, { _, result -> completed = result })
        var showing by mutableStateOf(true)
        val working = row.copy(status = "working")
        compose.setContent { WatchConsoleTheme { Column { if (showing) WatchInterruptAction(working, session) } } }
        compose.onNodeWithText("STOP").performClick()
        compose.onNodeWithText("Confirm stop").performClick()
        compose.runOnIdle { showing = false }
        compose.runOnIdle { reply.complete(WatchInterruptResult.STOPPED.toBytes()) }
        compose.waitForIdle()
        assertEquals(WatchInterruptResult.STOPPED, completed)
        scope.cancel()
    }
    // Catches TalkBack activation granting permission without confirmation or
    // having no usable click path; pointer-hold and static semantics checks miss it.
    @Test @Config(qualifiers = "h1200dp") fun accessibleAllowRequiresConfirmationBeforeSending() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sent = mutableListOf<WatchCommand>()
        val session = WatchDetailSession(scope, { _, bytes -> sent += requireNotNull(WatchCommand.fromBytes(bytes)); WatchResult.SENT.toBytes() })
        val permission = row.copy(requestId = "permission:edit", choices = listOf("Allow", "Deny"), canAnswerOnWatch = true)
        compose.setContent { WatchConsoleTheme { WatchAnswerActions(permission, session) } }
        compose.onNodeWithContentDescription("Hold to allow").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        assertTrue(sent.isEmpty())
        compose.onNodeWithText("Allow this request?").assertExists()
        compose.onNodeWithText("Allow", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, sent.size)
        assertEquals(0, sent.single().choice)
        scope.cancel()
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
