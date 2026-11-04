package io.github.shiroel.aries.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression: switching detail tabs must retain the draft and append a selected diff without sending;
 * Changes must remain lazy and reuse its per-Agent result across visits. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksDetailTest {
    init { System.getenv("ARIES_TASKS_FONT")?.toFloatOrNull()?.let(org.robolectric.RuntimeEnvironment::setFontScale) }
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun screenshot(name: String) {
        System.getenv("ARIES_TASKS_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun activityTerminalAndChangesKeepOneDraftAndOnlyLoadChangesWhenOpened() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("home.test", 22u, "me"))
            terminals = listOf(TerminalInfo("t1", "Review the task details", "", listOf("codex"), "/repo", "live", AgentStatus.Done))
        }
        val prefs = context.getSharedPreferences("tasks-detail-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = ThemeSettings(prefs).apply {
            chooseLayout(AppLayout.TASKS)
            chooseMode(if (System.getenv("ARIES_TASKS_THEME") == "dark") ThemeMode.DARK else ThemeMode.LIGHT)
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope).apply { start(layout = AppLayout.TASKS) } }
            AriesApp(model, settings)
        }
        compose.onNodeWithTag("task-row:h1:t1").performClick()
        compose.runOnIdle { model.switchView(AgentView.CHAT) }
        lateinit var chat: ChatModel
        compose.runOnIdle {
            chat = (model.screen as Screen.Chat).chat
            chat.draft = "Review this"
            core.pushChat(ChatSnapshot(listOf(
                ChatEntry("user", "2026-11-04T16:03:00Z", ChatEntryBody.User("Make task details easier to review")),
                ChatEntry("plan", "2026-11-04T16:04:00Z", ChatEntryBody.Tool("update_plan", "Review plan",
                    """{"plan":[{"step":"Inspect source","status":"completed"},{"step":"Run checks","status":"in_progress"}]}""", ChatToolStatus.DONE, null)),
                ChatEntry("edit", "2026-11-04T16:05:00Z", ChatEntryBody.Tool("apply_patch", "ui/Task.kt", "{}", ChatToolStatus.DONE, "Updated file",
                    ChatDiff("ui/Task.kt", listOf(ChatDiffHunk(1u, 1u, listOf("-old", "+hello", "+world"))), false))),
                ChatEntry("agent", "2026-11-04T16:06:00Z", ChatEntryBody.Agent("The changes are ready to review.")),
            ), ChatStatus.LIVE, null))
            core.connection!!.gitFiles = GitChanges("/repo", listOf(GitFile("ui/Task.kt", null, " M", false, true, false)), false, false)
            core.connection!!.gitPatch = GitDiff(listOf(GitDiffLine("-old", 1u, null), GitDiffLine("+hello", null, 1u), GitDiffLine("+world", null, 2u)), false, false)
            core.connection!!.gitTotals = GitStats(2u, 1u, false, false)
            assertEquals(0, core.connection!!.gitChangesRequests)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("The changes are ready to review.").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("activity")
        compose.onNodeWithTag("chat-transcript").performScrollToIndex(0)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Make task details easier to review").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("request-plan")
        compose.onNodeWithTag("tasks-tab-terminal").performScrollTo().performClick()
        compose.runOnIdle {
            val lines = listOf("$ codex", "", "Review the task details", "", "Updated ui/Task.kt", "+2 -1", "", "Ready for follow-up")
            core.connection!!.views.last().push(TerminalUpdate.Screen(ScreenFrame(40u, 24u, true,
                lines.mapIndexed { row, text -> ScreenLine(row.toUShort(), listOf(
                    CellRun(0u, text.length.toUShort(), text, false, null, null, false, false, false, false, false, false))) },
                CursorPos(8u, 0u), null)))
        }
        compose.waitForIdle()
        screenshot("terminal")
        compose.onNodeWithTag("tasks-tab-activity").performScrollTo().performClick()
        compose.runOnIdle { assertSame(chat, (model.screen as Screen.Chat).chat); assertEquals("Review this", chat.draft) }
        compose.onNodeWithTag("tasks-tab-changes").performScrollTo().performClick()
        compose.onNodeWithText("ui/Task.kt").performClick()
        screenshot("changes-diff")
        compose.onNodeWithText("+hello").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.git_send_selection)).performClick()
        compose.runOnIdle {
            assertSame(chat, (model.screen as Screen.Chat).chat)
            assertEquals("Review this\n\n/repo/ui/Task.kt:1\n```diff\n+hello\n```", chat.draft)
            assertTrue(core.connection!!.typed.isEmpty())
            assertEquals(1, core.connection!!.gitChangesRequests)
        }
        compose.onNodeWithTag("tasks-tab-changes").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, core.connection!!.gitChangesRequests) }
        compose.onNodeWithTag("tasks-tab-activity").performScrollTo().performClick()
        compose.runOnIdle { model.closeChat() }
        compose.onNodeWithText("+2 −1").assertExists()
        screenshot("home-stats")
    }
}
