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
        val live = row.copy(requestId = "permission:edit", choices = listOf("Allow", "Deny"), canAnswerOnWatch = true)
        val session = WatchDetailSession(scope, { _, _ -> WatchCommand(live).toBytes() })
        compose.setContent { WatchInbox(session.summary, 1, row.hostId to row.terminal, {}, false, null, {}, {}, detailActions = { WatchAnswerActions(it, session) }) }
        compose.runOnIdle { session.merge(summary(row)); session.load(row) }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Deny"))
        compose.onNodeWithText("Deny").assertExists()
        screenshot("permission-actions")
        compose.runOnIdle { session.merge(summary(row)) }
        compose.onNodeWithText("Deny").assertExists()
        compose.runOnIdle { session.merge(summary(row.copy(contentHidden = true))) }
        compose.onNodeWithText("Deny").assertDoesNotExist()
        compose.runOnIdle { session.merge(summary(row.copy(stateToken = "turn2"))) }
        compose.onNodeWithText("Deny").assertDoesNotExist()
        scope.cancel()
    }

    // Catches a late send reply being lost when a new request/token replaces
    // the row while the RPC is still pending, allowing an accidental repeat.
    @Test fun answerOutcomeSurvivesAReplacementRequestAndDetailReentry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reply = CompletableDeferred<ByteArray>()
        val session = WatchDetailSession(scope, { _, _ -> reply.await() })
        var showing by mutableStateOf(true)
        val question = row.copy(requestKind = "question", requestId = "q1", choices = listOf("Blue"), canAnswerOnWatch = true)
        compose.runOnIdle { session.merge(summary(question)) }
        compose.setContent { if (showing) WatchInbox(session.summary, 1, row.hostId to row.terminal, {}, false, null, {}, {}, detailActions = { WatchAnswerActions(it, session) }) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Blue"))
        compose.onNodeWithText("Blue").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sending…"))
        compose.onNodeWithText("Sending…").assertExists()
        screenshot("answer-sending")
        compose.runOnIdle { showing = false; session.merge(summary(question.copy(stateToken = "turn2", requestId = "q2"))) }
        compose.runOnIdle { reply.complete(WatchResult.SENT.toBytes()) }
        compose.runOnIdle { showing = true }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sent"))
        compose.onNodeWithText("Sent").assertExists()
        screenshot("answer-sent-after-reentry")
        compose.onNodeWithText("Blue").assertDoesNotExist()
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
