package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import androidx.compose.ui.test.onAllNodesWithContentDescription
import io.github.shiroel.aries.core.ChatDiff
import io.github.shiroel.aries.core.ChatDiffHunk
import io.github.shiroel.aries.core.AnswerProblem
import io.github.shiroel.aries.core.ChatQuestionOption
import io.github.shiroel.aries.core.ChatQuestionPart
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatImage
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.ChatToolStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An Agent's Chat, opened from the Agent list, against a fake core. */
@RunWith(RobolectricTestRunner::class)
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val claude = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)
    private val vim = TerminalInfo("t2", "vim", "", listOf("vim"), "/repo", "live", null, false, 0uL)

    private fun entry(id: String, body: ChatEntryBody) = ChatEntry(id, null, body)

    private val write = entry(
        "toolu_1",
        ChatEntryBody.Tool(
            "Write", "/repo/hello.txt", "{\n  \"file_path\": \"/repo/hello.txt\",\n  \"content\": \"hi\"\n}",
            ChatToolStatus.DONE, "File created successfully",
        ),
    )

    private fun openChat(core: FakeCore) {
        core.terminals = listOf(claude, vim)
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun opensFromTheAgentListAndShowsTheConversationLive() {
        val core = FakeCore()
        openChat(core)
        assertEquals(listOf("h1" to "t1"), core.chatsOpened)
        compose.onNodeWithText(str(R.string.chat_title, "claude")).assertExists()
        compose.onNodeWithText(str(R.string.chat_status_connecting)).assertExists()

        core.pushChat(
            ChatSnapshot(
                listOf(
                    entry("u1", ChatEntryBody.User("Create hello.txt")),
                    entry("th", ChatEntryBody.Thinking(null)),
                    write,
                ),
                ChatStatus.LIVE, "c:1",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Create hello.txt").assertExists()
        compose.onNodeWithText(str(R.string.chat_thinking)).assertExists()
        compose.onNodeWithText("Write").assertExists()
        compose.onNodeWithText(str(R.string.chat_status_connecting)).assertDoesNotExist()

        // Live: the reply and an error arrive later.
        core.pushChat(
            ChatSnapshot(
                listOf(
                    entry("u1", ChatEntryBody.User("Create hello.txt")),
                    entry("th", ChatEntryBody.Thinking(null)),
                    write,
                    entry("a1", ChatEntryBody.Agent("Done.")),
                    entry("e1", ChatEntryBody.AgentError("model not found")),
                ),
                ChatStatus.LIVE, "c:2",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Done.").assertExists()
        compose.onNodeWithText("model not found").assertExists()
        compose.onNodeWithText(str(R.string.chat_agent_error)).assertExists()
    }

    /** Pi's `/tree`: a new branch replaces the abandoned one's entries. */
    @Test
    fun entriesOfAnAbandonedBranchLeaveTheChat() {
        val core = FakeCore()
        openChat(core)
        val first = listOf(
            entry("u1", ChatEntryBody.User("first question")),
            entry("a1", ChatEntryBody.Agent("first answer")),
        )
        core.pushChat(
            ChatSnapshot(first + entry("u2", ChatEntryBody.User("branch A question")), ChatStatus.LIVE, "pi:s:1"),
        )
        compose.waitForIdle()
        compose.onNodeWithText("branch A question").assertExists()

        core.pushChat(
            ChatSnapshot(first + entry("u3", ChatEntryBody.User("branch B question")), ChatStatus.LIVE, "pi:s:2"),
        )
        compose.waitForIdle()
        compose.onNodeWithText("branch A question").assertDoesNotExist()
        compose.onNodeWithText("branch B question").assertExists()
        compose.onNodeWithText("first answer").assertExists()
    }

    @Test
    fun aToolCallIsCollapsedUntilTapped() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(write), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText("File created successfully").assertDoesNotExist()

        compose.onNodeWithContentDescription(str(R.string.chat_tool_expand_label, "Write")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("File created successfully").assertExists()
        compose.onNodeWithText(str(R.string.chat_tool_input)).assertExists()
        compose.onNodeWithText(write.body.let { (it as ChatEntryBody.Tool).input }).assertExists()

        compose.onNodeWithContentDescription(str(R.string.chat_tool_expand_label, "Write")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("File created successfully").assertDoesNotExist()
    }

    private fun edit(lines: List<String>, truncated: Boolean = false) = entry(
        "toolu_2",
        ChatEntryBody.Tool(
            "Edit", "/repo/hello.txt", "{}", ChatToolStatus.DONE, "The file has been updated.",
            diff = ChatDiff("/repo/hello.txt", listOf(ChatDiffHunk(1u, 1u, lines)), truncated),
        ),
    )

    @Test
    fun anEditShowsAsItsDiffWithoutExpanding() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(edit(listOf(" keep", "-hi", "+hello"))), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText("-hi", substring = true).assertExists()
        compose.onNodeWithText("+hello", substring = true).assertExists()
        compose.onNodeWithText("@@ -1 +1 @@", substring = true).assertExists()
        // The raw input stays behind the tap.
        compose.onNodeWithText(str(R.string.chat_tool_input)).assertDoesNotExist()
    }

    @Test
    fun aHugeDiffShowsAPreviewUntilTapped() {
        val core = FakeCore()
        openChat(core)
        val lines = (1..2000).map { "+line $it" }
        core.pushChat(ChatSnapshot(listOf(edit(lines, truncated = true)), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText("+line 1\n", substring = true).assertExists()
        compose.onNodeWithText("+line 2000", substring = true).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.chat_diff_more, 2000 - 12)).assertExists()

        compose.onNodeWithContentDescription(str(R.string.chat_tool_expand_label, "Edit")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("+line 2000", substring = true).assertExists()
        compose.onNodeWithText(str(R.string.chat_diff_truncated)).assertExists()
    }

    @Test
    fun imagesShowAndATooLargeOneIsAPlaceholder() {
        val core = FakeCore()
        core.chatImages["u1#0"] = PNG
        openChat(core)
        core.pushChat(
            ChatSnapshot(
                listOf(
                    entry(
                        "u1",
                        ChatEntryBody.User(
                            "what is this?",
                            listOf(
                                ChatImage("u1#0", "image/png", 75uL, 8u, 8u, true),
                                ChatImage("u1#1", "image/jpeg", 9_000_000uL, null, null, false),
                            ),
                        ),
                    ),
                ),
                ChatStatus.LIVE, null,
            ),
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(str(R.string.chat_image)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("what is this?").assertExists()
        compose.onNodeWithText(str(R.string.chat_image_too_large, "8.6")).assertExists()
    }

    @Test
    fun showsWhyThereIsNoChatAndWhatANewerBeaconSent() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(
            ChatSnapshot(listOf(entry("q", ChatEntryBody.Unsupported)), ChatStatus.RECONNECTING, null),
        )
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_unsupported)).assertExists()
        compose.onNodeWithText(str(R.string.chat_status_reconnecting)).assertExists()

        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.NO_BEACON, null))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_status_no_beacon)).assertExists()
    }

    @Test
    fun opensFromTheAgentsListAndReturnsToTheAgent() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            addHost("server", Endpoint.Ssh("10.0.0.3", 22u, "me"))
            terminalsOf["h2"] = listOf(claude)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.openConsoleDrawer()
        compose.onNodeWithText(str(R.string.agents_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_open_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("h2" to "t1"), core.chatsOpened)
        compose.onNodeWithText(str(R.string.chat_title, "claude")).assertExists()

        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(core.chatStopped)
        compose.onNodeWithText(str(R.string.agent_detail_host, "server")).assertExists()
    }

    private fun option(label: String) = ChatQuestionOption(label, "Choose $label")

    private fun color(status: ChatToolStatus, output: String? = null) = entry(
        "toolu_q",
        ChatEntryBody.Question(
            "AskUserQuestion", "Which color do you prefer?",
            listOf(ChatQuestionPart("Which color do you prefer?", "Color", false, listOf(option("Red"), option("Blue")))),
            status, output,
        ),
    )

    @Test
    fun anOpenQuestionIsAnsweredWithOneTap() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(color(ChatToolStatus.RUNNING)), ChatStatus.LIVE, null))
        compose.waitForIdle()
        compose.onNodeWithText("Which color do you prefer?").assertExists()
        compose.onNodeWithText("Choose Blue").assertExists()

        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("t1", "toolu_q", listOf(listOf(1u)))), core.answers)
        compose.onNodeWithText(str(R.string.chat_question_sent)).assertExists()

        // The Agent took it: no buttons, the answer shows.
        core.pushChat(
            ChatSnapshot(listOf(color(ChatToolStatus.DONE, "\"Which color do you prefer?\"=\"Blue\"")), ChatStatus.LIVE, null),
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Blue")).assertDoesNotExist()
        compose.onNodeWithText("\"Which color do you prefer?\"=\"Blue\"").assertExists()
    }

    @Test
    @Config(qualifiers = "h1200dp") // the whole card on screen
    fun severalQuestionsAreChosenThenSentTogether() {
        val core = FakeCore()
        openChat(core)
        val q = entry(
            "toolu_m",
            ChatEntryBody.Question(
                "AskUserQuestion", "Which size?",
                listOf(
                    ChatQuestionPart("Which size?", "Size", false, listOf(option("S"), option("M"))),
                    ChatQuestionPart("Which toppings?", "Toppings", true, listOf(option("Cheese"), option("Ham"), option("Olive"))),
                ),
                ChatToolStatus.RUNNING, null,
            ),
        )
        core.pushChat(ChatSnapshot(listOf(q), ChatStatus.LIVE, null))
        compose.waitForIdle()
        val send = compose.onNodeWithText(str(R.string.chat_question_send))
        send.performClick()
        compose.waitForIdle()
        assertTrue("nothing is sent before every question has a pick", core.answers.isEmpty())

        for (label in listOf("M", "Cheese", "Ham", "Olive", "Ham")) {
            compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, label)).performClick()
        }
        compose.waitForIdle()
        assertTrue(core.answers.isEmpty())
        send.performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("t1", "toolu_m", listOf(listOf(1u), listOf(0u, 2u)))), core.answers)
    }

    @Test
    fun aQuestionAnsweredElsewhereSaysSo() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(ChatSnapshot(listOf(color(ChatToolStatus.RUNNING)), ChatStatus.LIVE, null))
        compose.waitForIdle()
        core.answerFailure = CoreException.AnswerRefused(AnswerProblem.ANSWERED, "")
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Red")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_answer_answered)).assertExists()

        core.answerFailure = CoreException.AnswerRefused(AnswerProblem.GONE, "")
        compose.onNodeWithContentDescription(str(R.string.chat_question_option_label, "Red")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.chat_answer_gone)).assertExists()
    }

    @Test
    fun backLeavesTheChatAndStopsFollowing() {
        val core = FakeCore()
        openChat(core)
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(core.chatStopped)
        compose.onNodeWithText("claude").assertExists()
        compose.onNodeWithText(str(R.string.chat_title, "claude")).assertDoesNotExist()
    }

    private companion object {
        /** The 8x8 red PNG of the Claude Code fixture. */
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEklEQVR4nGP4z8CAFWEXHbQSACj/P8Fu7N9hAAAAAElFTkSuQmCC",
        )
    }
}
