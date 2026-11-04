package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GithubScreenTest {
    init {
        System.getenv("ARIES_GITHUB_FONT_SCALE")?.toFloatOrNull()?.let {
            org.robolectric.RuntimeEnvironment.setFontScale(it)
        }
    }
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    private var render by mutableStateOf(true)
    private lateinit var model: HostsModel

    private fun open(configure: (FakeConnection) -> Unit = {}): FakeConnection {
        val core = FakeCore()
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/launch", "live", AgentStatus.Working))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { if (render) AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        compose.runOnIdle { configure(core.connection!!) }
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(str(R.string.github_button)).performClick()
        return core.connection!!
    }

    private fun screenshot(name: String) {
        System.getenv("ARIES_GITHUB_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            val suffix = if (System.getenv("ARIES_GITHUB_FONT_SCALE") == "2") "-font-200" else ""
            File(directory, "$name$suffix.png").outputStream().use {
                compose.onNodeWithTag("github-dialog").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    // Regression: a reconnect must not hydrate stale disk text over a draft whose save failed.
    @Test fun reconnectKeepsUnsavedDraftAndRetryPersistsIt() {
        val conn = open { it.githubDraftSaveFailure = CoreException.Closed() }
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithTag("github-comment").performTextInput("Keep this unsaved comment")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(str(R.string.github_draft_failed)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            val link = (model.screen as Screen.Terminal).viewer.link!!
            link.lost(conn)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("github-comment").assertTextContains("Keep this unsaved comment")
        compose.runOnIdle { conn.githubDraftSaveFailure = null }
        compose.onNodeWithTag("github-draft-retry").performClick()
        compose.waitUntil(5_000) { conn.githubDrafts["octo/project" to 42u] == "Keep this unsaved comment" }
        compose.runOnIdle { assertFalse(conn.githubDraftAccessedOnMain) }
    }

    // Regression: a reply delayed past UI recreation must finish once and clear the draft.
    @Test fun acceptedCommentFinishesAcrossRecreationWithoutOfferingItAgain() {
        val reply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val conn = open { it.githubCommentReply = reply }
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithTag("github-comment").performTextInput("Post exactly once")
        compose.onNodeWithText(str(R.string.github_send)).performClick()
        compose.waitUntil(5_000) { conn.githubComments.size == 1 }
        compose.runOnIdle { render = false }
        compose.runOnIdle { reply.complete(Unit) }
        compose.runOnIdle { render = true }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("github-comment").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("github-comment").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText(str(R.string.github_send)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, conn.githubComments.size) }
    }

    // Regression: delayed refreshes must leave the last readable thread/list on screen.
    @Test fun refreshKeepsThreadAndListReadableAndBackDoesNotRefetch() {
        val conn = open()
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithText("Issue body").assertExists()
        val detailReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        compose.runOnIdle { conn.githubDetailReply = detailReply }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithText("Issue body").assertExists()
        compose.runOnIdle { detailReply.complete(Unit) }
        compose.waitForIdle()
        var requests = 0
        compose.runOnIdle { requests = conn.githubRequests.size }
        compose.onNodeWithTag("github-back").performClick()
        compose.onNodeWithText("Issue title").assertExists()
        compose.runOnIdle { assertEquals(requests, conn.githubRequests.size) }
        val listReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        compose.runOnIdle { conn.githubListReply = listReply }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithText("Issue title").assertExists()
        compose.runOnIdle { listReply.complete(Unit) }
        screenshot("list")
    }

    // Regression: a failed local read needs a working recovery path without leaving the item.
    @Test fun draftReadFailureCanBeRetriedWithoutLosingTheSavedComment() {
        val conn = open {
            it.githubDrafts["octo/project" to 42u] = "Previously saved"
            it.githubDraftReadFailure = CoreException.Closed()
        }
        compose.onNodeWithText("Issue title").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(str(R.string.github_draft_read_failed)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("github-comment").assertIsNotEnabled()
        screenshot("draft-read-retry")
        compose.runOnIdle { conn.githubDraftReadFailure = null }
        compose.onNodeWithTag("github-draft-retry").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("github-comment").filter(hasText("Previously saved")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("github-comment").assertIsEnabled()
    }

    // Regression: local storage failure must not block an explicit post or restore its text after success.
    @Test fun successfulPostClearsMemoryEvenWhenSavingTheDraftFails() {
        val conn = open { it.githubDraftSaveFailure = CoreException.Closed() }
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithTag("github-comment").performTextInput("Post despite full storage")
        compose.onNodeWithText(str(R.string.github_send)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(str(R.string.github_sent)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("github-comment").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { conn.githubDraftSaveFailure = null }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("github-draft-retry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("github-draft-retry").performClick()
        compose.waitUntil(5_000) { conn.githubDrafts["octo/project" to 42u] == "" }
        compose.runOnIdle { assertEquals(1, conn.githubComments.size) }
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
        fun assertRenderedDraft() {
            // Markdown parses off the Compose thread before its merged text is ready.
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("draft markdown").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("draft markdown").assertExists()
        }
        compose.onNodeWithText("Issue title").performClick()
        compose.onNodeWithText("Issue body").assertExists()
        compose.onNodeWithTag("github-comment").performTextInput("**draft markdown**")
        compose.onNodeWithText(str(R.string.github_preview)).performClick()
        assertRenderedDraft()
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
        assertRenderedDraft()
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
