package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class GitChangesTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    private fun openAgent(): Pair<FakeCore, HostsModel> {
        val core = FakeCore()
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/launch", "live", AgentStatus.Working))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.runOnIdle {
            core.connection!!.gitFiles = GitChanges("/repo", listOf(GitFile("x", null, " M", false, true, false)), false, false)
            core.connection!!.gitPatch = GitDiff(listOf(GitDiffLine("+hello", null, 3u)), false, false)
        }
        return core to model
    }

    @Test fun selectedDiffIsAppendedToChatDraftWithoutSending() {
        val (core, model) = openAgent()
        compose.onNodeWithText(str(R.string.git_changes_button)).performClick()
        compose.onNodeWithText("x").performClick()
        compose.onNodeWithText("+hello").performClick()
        compose.onNodeWithText(str(R.string.git_send_selection)).performClick()
        compose.runOnIdle {
            assertEquals("/repo/x:3\n```diff\n+hello\n```", (model.screen as Screen.Chat).chat.draft)
            assertEquals(emptyList<String>(), core.connection!!.typed.map { it.toString() })
        }
    }

    @Test fun sendingAFileFromChatKeepsTheExistingDraft() {
        val (core, model) = openAgent()
        compose.runOnIdle {
            model.switchView(AgentView.CHAT)
            (model.screen as Screen.Chat).chat.draft = "Review this"
        }
        compose.onNodeWithText(str(R.string.git_changes_button)).performClick()
        compose.onNodeWithText("x").performClick()
        compose.onNodeWithText(str(R.string.git_send_file)).performClick()
        compose.runOnIdle {
            assertEquals("Review this\n\n/repo/x:3\n```diff\n+hello\n```", (model.screen as Screen.Chat).chat.draft)
            assertEquals(emptyList<Pair<String, String>>(), core.connection!!.typed)
        }
    }

    @Test fun nonRepositoryCanBeRefreshedIntoACleanRepository() {
        val (core, _) = openAgent()
        compose.runOnIdle { core.connection!!.gitFiles = GitChanges("/outside", emptyList(), false, true) }
        compose.onNodeWithText(str(R.string.git_changes_button)).performClick()
        compose.onNodeWithText(str(R.string.git_not_repository)).assertExists()
        compose.runOnIdle { core.connection!!.gitFiles = GitChanges("/repo", emptyList(), false, false) }
        compose.onNodeWithText(str(R.string.git_retry_button)).performClick()
        compose.onNodeWithText(str(R.string.git_clean)).assertExists()
    }

    @Test fun largeDiffRevealsAnotherChunkAndShowsTheHostLimit() {
        val (core, _) = openAgent()
        compose.runOnIdle {
            core.connection!!.gitPatch = GitDiff((1..205).map { GitDiffLine("+line $it", null, it.toUInt()) }, false, true)
        }
        compose.onNodeWithText(str(R.string.git_changes_button)).performClick()
        compose.onNodeWithText("x").performClick()
        compose.onNodeWithText(str(R.string.git_truncated)).assertExists()
        compose.onNodeWithText("+line 205").assertDoesNotExist()
        compose.onNodeWithTag("git-diff-lines").performScrollToIndex(200)
        compose.onNodeWithText(str(R.string.git_more_button)).performClick()
        compose.onNodeWithTag("git-diff-lines").performScrollToIndex(204)
        compose.onNodeWithText("+line 205").assertExists()
    }

}
