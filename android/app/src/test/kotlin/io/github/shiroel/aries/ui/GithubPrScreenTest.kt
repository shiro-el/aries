package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GithubPrScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)
    private var visible by mutableStateOf(true)

    private fun open(): FakeConnection {
        val core = FakeCore()
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/launch", "live", AgentStatus.Working))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            val saved = rememberSaveableStateHolder()
            MaterialTheme { if (visible) saved.SaveableStateProvider("unlocked") { AriesApp(model) } }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        val conn = core.connection!!
        compose.runOnIdle {
            conn.githubPage = conn.githubPage.copy(items = conn.githubPage.items.map { it.copy(kind = GithubKind.PULL_REQUEST, title = "PR title") })
            conn.githubPrFiles = GithubPrFiles(listOf(
                GithubPrFile("src/새 이름.rs", "src/old.rs", "renamed", 1u, 1u,
                    GitDiff(listOf(GitDiffLine("@@ -7 +7 @@", null, null), GitDiffLine("-old", 7u, null), GitDiffLine("+새 코드", null, 7u)), false, false)),
                GithubPrFile("image.png", null, "added", 0u, 0u, null),
            ), false)
            conn.githubPrCommits = GithubPrCommits(listOf(GithubPrCommit("a".repeat(40), "Fix parser\n\nFull commit body", "octocat", "2026-11-04")), false)
            conn.githubPrChecks = GithubPrChecks("c".repeat(40), listOf(GithubPrCheck("Android CI", "completed", "failure", ""), GithubPrCheck("External CI", "pending", null, "Build queued")))
            conn.githubPrReviews = listOf(GithubPrReview(1uL, "reviewer", "CHANGES_REQUESTED", "**Please fix** parser", "2026-11-04"))
        }
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithText(str(R.string.github_button)).performClick()
        compose.onNodeWithText("PR title").performClick()
        return conn
    }

    private fun tab(name: String) { compose.onNodeWithTag("github-pr-tab-$name").performScrollTo().performClick() }

    private fun screenshot(name: String) {
        System.getenv("ARIES_PR_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                compose.onNodeWithTag("github-dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun resourceTabsKeepTheConversationDraftAndPatchBackStaysInThePullRequest() {
        val conn = open()
        compose.onNodeWithTag("github-comment").performTextInput("Unposted draft")
        tab("files")
        compose.onNodeWithText("src/새 이름.rs").assertExists()
        screenshot("files")
        compose.onNodeWithText("src/새 이름.rs").performClick()
        compose.onNodeWithText("+새 코드").assertExists()
        screenshot("patch")
        // Regression: restoring a locked composition must retain both the PR tab
        // and opened patch; initial read effects must not discard that selection.
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        screenshot("patch-after-unlock")
        compose.onNodeWithText("+새 코드").assertExists()
        compose.onNodeWithTag("github-back").performClick()
        compose.onNodeWithText("image.png").performClick()
        compose.onNodeWithText(str(R.string.github_pr_patch_unavailable)).assertExists()
        compose.onNodeWithTag("github-back").performClick()
        tab("commits")
        compose.onNodeWithText("Fix parser\n\nFull commit body").assertExists()
        tab("checks")
        compose.onNodeWithText(str(R.string.github_pr_failure)).assertExists()
        compose.onNodeWithText(str(R.string.github_pr_pending)).assertExists()
        screenshot("checks")
        tab("reviews")
        // Markdown is parsed on Dispatchers.Default, outside Compose's idle synchronization.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Please fix parser").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Please fix parser").assertExists()
        screenshot("reviews")
        tab("conversation")
        compose.onNodeWithTag("github-comment").assertTextContains("Unposted draft")
        compose.runOnIdle {
            assertEquals(emptyList<Triple<GithubKind, UInt, String>>(), conn.githubComments)
            assertEquals(emptyList<Pair<UInt, Boolean>>(), conn.githubStateChanges)
        }
    }

    @Test fun failedRefreshClearsTheOldPatchAndCanRecoverToAnEmptyFileList() {
        val conn = open()
        tab("files")
        compose.onNodeWithText("src/새 이름.rs").performClick()
        compose.onNodeWithText("+새 코드").assertExists()
        compose.runOnIdle { conn.githubPrFailure = CoreException.Closed() }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithText(str(R.string.github_disconnected)).assertExists()
        compose.onNodeWithText("+새 코드").assertDoesNotExist()
        compose.runOnIdle {
            conn.githubPrFailure = null
            conn.githubPrFiles = GithubPrFiles(emptyList(), false)
        }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithText(str(R.string.github_pr_no_files)).assertExists()
        compose.onNodeWithText(str(R.string.github_disconnected)).assertDoesNotExist()
    }

    @Test fun overviewDoesNotReportPendingOrCancelledChecksAsPassedAndClearsStaleHealth() {
        val conn = open()
        compose.runOnIdle {
            conn.githubPrChecks = GithubPrChecks("d".repeat(40), listOf(
                GithubPrCheck("Build", "completed", "success", ""),
                GithubPrCheck("External CI", "pending", null, "Queued"),
            ))
        }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithText(str(R.string.github_checks_pending)).assertExists()
        compose.onNodeWithTag("github-checks-summary").performClick()
        compose.onNodeWithText(str(R.string.github_pr_pending)).assertExists()
        tab("conversation")
        compose.onNodeWithText("src/새 이름.rs").performScrollTo().performClick()
        compose.onNodeWithText("+새 코드").assertExists()
        compose.onNodeWithTag("github-back").performClick()
        tab("conversation")
        compose.runOnIdle {
            conn.githubPrChecks = GithubPrChecks("e".repeat(40), listOf(
                GithubPrCheck("Build", "completed", "cancelled", ""),
            ))
        }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithText(str(R.string.github_checks_other)).assertExists()
        compose.onNodeWithText(str(R.string.github_checks_passed)).assertDoesNotExist()
        compose.runOnIdle { conn.githubPrFailure = CoreException.Closed() }
        compose.onNodeWithContentDescription(str(R.string.github_refresh)).performClick()
        compose.onNodeWithTag("github-checks-summary").assertTextContains(str(R.string.github_disconnected))
        compose.onNodeWithText(str(R.string.github_checks_pending)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.github_checks_other)).assertDoesNotExist()
    }
}
