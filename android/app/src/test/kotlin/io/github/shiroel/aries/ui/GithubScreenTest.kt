package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
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
class GithubScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    private fun open(configure: (FakeConnection) -> Unit = {}): FakeConnection {
        val core = FakeCore()
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/launch", "live", AgentStatus.Working))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.runOnIdle { configure(core.connection!!) }
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(str(R.string.github_button)).performClick()
        return core.connection!!
    }

    @Test fun filtersAndSearchResetPaginationAndKeepTheSelectedRepository() {
        val conn = open()
        compose.onNodeWithText("octo/project").assertExists()
        compose.onNodeWithContentDescription(str(R.string.github_next)).performClick()
        compose.onNodeWithText(str(R.string.github_prs)).performClick()
        compose.onNodeWithTag("github-state-filter").performClick()
        compose.onNodeWithText(str(R.string.github_closed)).performClick()
        compose.onNodeWithTag("github-search").performTextInput("search term")
        compose.onNodeWithContentDescription(str(R.string.github_search_button)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("PULL_REQUEST", "CLOSED", "search term", "1"), conn.githubRequests.last())
            assertEquals(1, conn.githubRepoReads)
        }
        compose.onNodeWithText("Issue title").assertExists()
    }

    @Test fun draftAndMarkdownPreviewSurviveNavigationAndFailedPost() {
        val conn = open()
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithText("Issue body").assertExists()
        compose.onNodeWithTag("github-comment").performTextInput("**draft markdown**")
        compose.onNodeWithText(str(R.string.github_preview)).performClick()
        compose.onNodeWithText("draft markdown").assertExists()
        compose.runOnIdle { assertEquals(emptyList<Triple<GithubKind, UInt, String>>(), conn.githubComments) }
        compose.onNodeWithText(str(R.string.github_edit)).performClick()
        compose.onNodeWithTag("github-back").performClick()
        compose.onNodeWithTag("github-back").performClick()
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(str(R.string.github_button)).performClick()
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithTag("github-comment").assertTextContains("**draft markdown**")
        compose.runOnIdle { conn.githubFailure = CoreException.Closed() }
        compose.onNodeWithText(str(R.string.github_send)).performClick()
        compose.onNodeWithText(str(R.string.github_unknown)).assertExists()
        compose.onNodeWithTag("github-comment").assertTextContains("**draft markdown**")
        compose.runOnIdle { conn.githubFailure = null }
        compose.onNodeWithText(str(R.string.github_send)).performClick()
        compose.onNodeWithText(str(R.string.github_sent)).assertExists()
        compose.onNodeWithText("draft markdown").assertExists()
        compose.onNodeWithTag("github-comment").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle {
            assertEquals(listOf(Triple(GithubKind.ISSUE, 42u, "**draft markdown**"), Triple(GithubKind.ISSUE, 42u, "**draft markdown**")), conn.githubComments)
            assertEquals("", conn.githubDrafts["octo/project" to 42u])
        }
    }

    @Test fun issueCanBeClosedAndReopened() {
        val conn = open()
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithContentDescription(str(R.string.github_item_actions)).performClick()
        compose.onNodeWithText(str(R.string.github_close_issue)).performClick()
        compose.onNodeWithContentDescription(str(R.string.github_item_actions)).performClick()
        compose.onNodeWithText(str(R.string.github_reopen_issue)).performClick()
        compose.onNodeWithContentDescription(str(R.string.github_item_actions)).performClick()
        compose.onNodeWithText(str(R.string.github_close_issue)).assertExists()
        compose.runOnIdle { assertEquals(listOf(42u to true, 42u to false), conn.githubStateChanges) }
    }

    @Test fun pullRequestHasConversationCommentsWithoutIssueLifecycleActions() {
        val conn = open {
            it.githubPage = it.githubPage.copy(items = it.githubPage.items.map { item -> item.copy(kind = GithubKind.PULL_REQUEST, title = "Pull request") })
        }
        compose.onNodeWithText("Pull request").performClick()
        compose.onNodeWithText(str(R.string.github_close_issue)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.github_reopen_issue)).assertDoesNotExist()
        compose.onNodeWithTag("github-comment").performTextInput("PR comment")
        compose.onNodeWithText(str(R.string.github_send)).performClick()
        compose.onNodeWithText(str(R.string.github_sent)).assertExists()
        compose.runOnIdle { assertEquals(listOf(Triple(GithubKind.PULL_REQUEST, 42u, "PR comment")), conn.githubComments) }
    }

    @Test fun missingGhAndLoginErrorsExplainHostSetupAndCanBeRefreshed() {
        val conn = open { it.githubFailure = CoreException.Rpc("github_missing", "") }
        compose.onNodeWithText(str(R.string.github_missing)).assertExists()
        compose.runOnIdle { conn.githubFailure = CoreException.Rpc("github_auth", "") }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithText(str(R.string.github_auth)).assertExists()
        compose.runOnIdle { conn.githubFailure = null }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithText("Issue title").assertExists()
    }
}
