package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ChatQuestionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun question(id: String, prompt: String = "Color?", name: String = "AskUserQuestion") = ChatEntry(id, null,
        ChatEntryBody.Question(name, prompt, listOf(ChatQuestionPart(prompt, null, false,
            listOf(ChatQuestionOption("Red", null), ChatQuestionOption("Blue", null)))), ChatToolStatus.RUNNING, null))

    private fun open(core: FakeCore, status: AgentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, null)) {
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", status, true, 0uL))
        core.savedView = AgentView.CHAT
        core.addHost("home", Endpoint.Ssh("host", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            val theme = remember { ThemeSettings.from(context).apply {
                chooseMode(if (System.getenv("ARIES_QUESTION_DARK") == "1") ThemeMode.DARK else ThemeMode.LIGHT)
            } }
            AriesTheme(theme) { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
    }

    private fun screenshot(name: String, dialog: Boolean = false) {
        System.getenv("ARIES_QUESTION_RENDER_DIR")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "$name.png").outputStream().use {
                val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
                node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun aPermissionCardUsesCheckedAnswers() {
        val core = FakeCore()
        open(core, AgentStatus.Blocked(BlockedReason.PERMISSION, null))
        val permission = ChatEntry("permission:current", null, ChatEntryBody.Question("permission", "Allow Write?",
            listOf(ChatQuestionPart("Create file /repo/file.txt\n+hello\nDo you want to create file.txt?", null, false, listOf(
                ChatQuestionOption("Allow", "Yes"), ChatQuestionOption("Allow always", "Yes, allow all edits in /repo during this session"),
                ChatQuestionOption("Deny", null)))), ChatToolStatus.RUNNING, null))
        core.pushChat(ChatSnapshot(listOf(permission), ChatStatus.LIVE, null))
        compose.waitForIdle()
        System.getenv("ARIES_PERMISSION_RENDER_DIR")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "permission-card.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        screenshot("permission")
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Deny")).performClick()
        compose.waitForIdle()
        assertEquals("permission:current", core.answers.single().second)
        assertEquals(listOf(listOf(2u)), core.answers.single().third)
        assertTrue(core.connection!!.typed.isEmpty())
    }

    // Regression: a scroll mis-tap on Allow always must not send a persistent
    // permission until explicitly confirmed, and cancellation must send nothing.
    @Test fun onlyAlwaysAllowRequiresConfirmationAndCancelSendsNothing() {
        val core = FakeCore()
        open(core, AgentStatus.Blocked(BlockedReason.PERMISSION, null))
        fun permission(id: String) = ChatEntry(id, null, ChatEntryBody.Question("permission", "Allow Write?",
            listOf(ChatQuestionPart("Create /repo/file.txt?", null, false, listOf(
                ChatQuestionOption("Allow", null), ChatQuestionOption("Allow always", "Allow all edits in /repo during this session"),
                ChatQuestionOption("Deny", null)))), ChatToolStatus.RUNNING, null))
        core.pushChat(ChatSnapshot(listOf(permission("permission:1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        val always = compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Allow always"), substring = true)
        always.performClick()
        compose.waitForIdle()
        assertTrue(core.answers.isEmpty())
        compose.onNode(isDialog()).assertExists()
        screenshot("always-confirm", dialog = true)
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        assertTrue(core.answers.isEmpty())
        always.performClick()
        compose.onNode(hasText(str(R.string.chat_permission_allow_always)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(listOf(listOf(1u)), core.answers.single().third)
        core.pushChat(ChatSnapshot(listOf(permission("permission:2")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Allow")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(listOf(0u)), core.answers.last().third)
        assertEquals(2, core.answers.size)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    // D24: losing the live key-delivery receipt must not let the user press the same answer twice.
    @Test fun anUnconfirmedLiveAnswerRequiresTerminalInsteadOfRetry() {
        val core = FakeCore().apply { answerFailure = CoreException.AnswerRefused(AnswerProblem.UNCONFIRMED, "receipt lost") }
        open(core)
        core.pushChat(ChatSnapshot(listOf(question("q-lost")), ChatStatus.LIVE, null))
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Red")).performClick()
        compose.onNodeWithText(str(R.string.chat_question_unconfirmed)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, core.answers.size) }
    }

    @Test fun freeTextUsesTheQuestionApiAndCannotBeSentTwice() {
        val core = FakeCore()
        open(core)
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_text_label, "Color?")).performTextInput("보라색")
        compose.onNodeWithText(str(R.string.chat_question_send)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(listOf<String?>("보라색")), core.textAnswers.map { it.second })
        assertTrue(core.connection!!.typed.isEmpty())
        compose.onNodeWithText(str(R.string.chat_question_send)).assertDoesNotExist()
        compose.onNodeWithText("보라색").assertExists()
        // A still-running transcript question must not offer to send the same answer after a view switch.
        compose.onNodeWithContentDescription(str(R.string.agent_switch_terminal_label)).performClick()
        compose.onNodeWithContentDescription(str(R.string.agent_switch_chat_label)).performClick()
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_question_send)).assertDoesNotExist()
        compose.onNodeWithText("보라색").assertExists()
        assertEquals(1, core.textAnswers.size)
    }

    // Regression: a successful key write can leave the same menu Blocked;
    // the local receipt must eventually offer a Terminal escape without resending.
    @Test fun anUnchangedQuestionAfterSendingOffersTerminalRecovery() {
        val core = FakeCore()
        open(core)
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        screenshot("sent-waiting")
        compose.mainClock.advanceTimeBy(4_000)
        screenshot("sent-still-blocked")
        compose.onNodeWithText(str(R.string.terminal_open_button)).assertExists()
        assertEquals(1, core.answers.size)
    }

    // Regression: transcript and structure watches can arrive in either order;
    // a fresh RUNNING menu must never send through the historical-message path.
    @Test fun aFreshQuestionWaitsForBlockedAndKeepsDisabledChoicesWhileReconnecting() {
        val core = FakeCore()
        open(core, AgentStatus.Working)
        val snapshot = ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null)
        core.pushChat(snapshot)
        compose.waitForIdle()
        val blue = compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue"))
        blue.assertIsNotEnabled().performClick()
        screenshot("live-pending")
        assertTrue(core.connection!!.typed.isEmpty())
        assertTrue(core.answers.isEmpty())
        core.push(core.terminals.map { it.copy(agentStatus = AgentStatus.Blocked(BlockedReason.QUESTION, null)) })
        compose.waitForIdle()
        blue.assertIsEnabled()
        core.pushChat(snapshot.copy(status = ChatStatus.RECONNECTING))
        compose.waitForIdle()
        blue.assertIsNotEnabled()
        screenshot("reconnecting")
        core.pushChat(snapshot)
        compose.waitForIdle()
        blue.assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("t1", "q1", listOf(listOf(1u)))), core.answers)
        assertTrue(core.connection!!.typed.isEmpty())
    }

    // Regression: historical answers use terminal.send and may be Held before
    // any bytes are written; the card must explain why it has not completed.
    @Test fun aHeldHistoryAnswerExplainsTheWaitAndCannotBeSubmittedAgain() {
        val core = FakeCore()
        open(core, AgentStatus.Idle)
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        core.connection!!.sendHold = hold
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        val blue = compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue"))
        blue.performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_send_held)).assertExists()
        screenshot("held")
        blue.assertIsNotEnabled().performClick()
        assertTrue(core.connection!!.typed.isEmpty())
        hold.complete(Unit)
        compose.waitForIdle()
        assertEquals(listOf("t1" to "Blue"), core.connection!!.typed)
    }

    // D15: losing only the terminal.send response must require confirmation before another answer.
    @Test fun aLostHistoryAnswerResponseRequiresAnExplicitResendConfirmation() {
        val core = FakeCore()
        open(core, AgentStatus.Idle)
        val response = kotlinx.coroutines.CompletableDeferred<Unit>()
        core.connection!!.sendAfterTypingReply = response
        core.connection!!.sendAfterTypingFailure = CoreException.Closed()
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_question_sending)).assertExists()
        assertEquals(listOf("t1" to "Blue"), core.connection!!.typed)
        response.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_queue_uncertain)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertDoesNotExist()
        screenshot("history-unconfirmed")
        compose.onNodeWithText(str(R.string.chat_delivery_retry_button)).performClick()
        compose.onNode(isDialog()).assertExists()
        screenshot("history-resend-confirm", dialog = true)
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        assertEquals(1, core.connection!!.calls.count { it == "sendMessage" })
        assertEquals(listOf("t1" to "Blue"), core.connection!!.typed)
        core.connection!!.sendAfterTypingFailure = null
        compose.onNodeWithText(str(R.string.chat_delivery_retry_button)).performClick()
        compose.onNode(hasText(str(R.string.chat_delivery_retry_button)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1" to "Blue", "t1" to "Blue"), core.connection!!.typed)
        assertEquals(2, core.connection!!.calls.count { it == "sendMessage" })
    }

    // D15 recovery: a process death while sending must not silently enable duplicate answers.
    @Test fun aJournalRestoresAnInterruptedHistoryAnswerAsUnconfirmed() {
        val core = FakeCore()
        core.saveChatOutbox("h1", "t1", ChatOutboxState("", emptyList(), emptyList(),
            picks = mapOf("q1" to listOf(setOf(1))), answers = mapOf("q1" to "sending")).encode())
        open(core, AgentStatus.Idle)
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_queue_uncertain)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertDoesNotExist()
        compose.onNode(hasText("Blue") and isSelected()).assertExists()
        compose.onNodeWithText(str(R.string.chat_delivery_retry_button)).performClick()
        compose.onNode(isDialog()).assertExists()
        assertTrue(core.connection!!.typed.isEmpty())
        assertFalse(core.connection!!.calls.contains("sendMessage"))
    }

    @Test fun queuedCodexRequestsAdvanceAfterTheFirstGetsAResult() {
        val core = FakeCore()
        open(core)
        val first = question("q1", "First?", "request_user_input")
        val second = question("q2", "Second?", "request_user_input")
        core.pushChat(ChatSnapshot(listOf(first, second), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertCountEquals(1)
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertEquals("q1", core.answers.single().second)
        val done = first.copy(body = (first.body as ChatEntryBody.Question).copy(status = ChatToolStatus.DONE, output = "Blue"))
        core.pushChat(ChatSnapshot(listOf(done, second), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Red")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("q1", "q2"), core.answers.map { it.second })
    }

    @Test fun latestUnansweredHistoryUsesAMessageAndOlderQuestionsStayReadOnly() {
        val core = FakeCore()
        open(core, AgentStatus.Idle)
        core.pushChat(ChatSnapshot(listOf(question("old", "Old?"), question("latest")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertCountEquals(1)
        compose.onNodeWithText(str(R.string.chat_question_history)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("t1" to "Blue"), core.connection!!.typed)
        assertTrue(core.answers.isEmpty())
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertDoesNotExist()
        compose.onNode(hasText("Blue") and isSelected()).assertExists()
    }
    @Test fun historyRefusedByANewPermissionShowsTheFailureAndTypesNothing() {
        val core = FakeCore()
        open(core, AgentStatus.Idle)
        core.connection!!.sendOutcome = SendOutcome.Blocked(BlockedReason.PERMISSION, "Allow Write?")
        core.pushChat(ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertTrue(core.connection!!.typed.isEmpty())
        compose.onNodeWithText(str(R.string.chat_answer_gone)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertDoesNotExist()
    }

    @Test fun hostAnswersStaySelectedBesideTheirPromptsAfterReopeningChat() {
        // Regression: no local picks exist after reopening; completed Host
        // answers used to lose their choices and appear as raw tool prose.
        val core = FakeCore()
        open(core, AgentStatus.Working)
        core.pushChat(ChatSnapshot(listOf(ChatEntry("host-q", null, ChatEntryBody.Question(
            "AskUserQuestion", "Scope?", listOf(
                ChatQuestionPart("Scope?", "Scope", true, listOf(
                    ChatQuestionOption("Chat", null), ChatQuestionOption("Terminal", null), ChatQuestionOption("Calls", null))),
                ChatQuestionPart("Color?", "Color", false, listOf(ChatQuestionOption("Blue", null))),
            ), ChatToolStatus.DONE,
            "User has answered your questions: \"Scope?\"=\"Chat, Calls\", \"Color?\"=\"Purple\". You can now continue.",
        ))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNode(hasText("Chat") and isSelected()).assertExists()
        compose.onNode(hasText("Calls") and isSelected()).assertExists()
        compose.onNodeWithText("Purple").assertExists()
        screenshot("recorded-answer")
        compose.onNodeWithText(str(R.string.chat_question_send)).assertDoesNotExist()
        assertTrue(core.answers.isEmpty())
    }

    @Test fun fourRecordedCodexAnswersKeepMultilineOtherAndNotesWithTheRightPrompt() {
        // Regression: the old newline-flattened output cannot distinguish
        // multiline Other text from the next answer in a multi-question call.
        val core = FakeCore()
        open(core, AgentStatus.Working)
        val parts = listOf(
            ChatQuestionPart("First?", "First", false, listOf(ChatQuestionOption("Blue", null)),
                ChatQuestionAnswer(listOf(0u), null, null)),
            ChatQuestionPart("Second?", "Second", false, listOf(ChatQuestionOption("Green", null)),
                ChatQuestionAnswer(emptyList(), "Purple\nViolet", "Use the darker shade")),
            ChatQuestionPart("Third?", "Third", true, listOf(ChatQuestionOption("A, B", null), ChatQuestionOption("C", null)),
                ChatQuestionAnswer(listOf(0u, 1u), null, null)),
            ChatQuestionPart("Fourth?", "Fourth", false, listOf(ChatQuestionOption("D", null)),
                ChatQuestionAnswer(emptyList(), "Custom fourth answer", null)),
        )
        core.pushChat(ChatSnapshot(listOf(ChatEntry("codex-q", null, ChatEntryBody.Question(
            "request_user_input", "First?", parts, ChatToolStatus.DONE, "{\"answers\":{}}",
        ))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNode(hasText("Blue") and isSelected()).assertExists()
        compose.onNode(hasText("A, B") and isSelected()).assertExists()
        compose.onNode(hasText("C") and isSelected()).assertExists()
        compose.onNodeWithText("Purple\nViolet").assertExists()
        compose.onNodeWithText("Use the darker shade").assertExists()
        compose.onNodeWithText("Custom fourth answer").assertExists()
        compose.onNodeWithText(str(R.string.chat_question_send)).assertDoesNotExist()
        assertTrue(core.answers.isEmpty())
    }



    @Test fun waitingUserInputDoesNotRetireAnOpenQuestion() {
        val core = FakeCore()
        open(core)
        val waiting = ChatEntry("queued-user", null, ChatEntryBody.User("Inspect the other file too.", queued = true))
        core.pushChat(ChatSnapshot(listOf(question("q1"), waiting), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertEquals("q1", core.answers.single().second)
        assertTrue(core.connection!!.typed.isEmpty())
    }
}
