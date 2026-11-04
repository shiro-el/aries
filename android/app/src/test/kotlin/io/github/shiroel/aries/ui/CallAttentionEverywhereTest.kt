package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class CallAttentionEverywhereTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: HostsModel
    private val core = FakeCore().apply {
        ensureKey()
        addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
    }
    private val call = CallInfo("h1", "check", 100uL, "t1", true,
        "Review the result", "Please check", CallUrgency.CHECK, emptyList(), null, true)

    private fun show(layout: AppLayout) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ThemeSettings(context.getSharedPreferences("attention-layout", Context.MODE_PRIVATE)).apply {
            chooseLayout(layout)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            LaunchedEffect(model) { model.start(layout = layout) }
            AriesApp(model, settings)
        }
        compose.waitForIdle()
    }

    // D01: Thread's home and management screens must show a newly arriving Call and open its inbox.
    @Test fun threadOutsideChatShowsNewCalls() {
        show(AppLayout.THREAD)
        compose.onNodeWithTag("thread-empty-home").assertIsDisplayed()
        compose.runOnIdle { core.sendCall(call) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-inbox-panel").assertIsDisplayed()
        compose.runOnIdle { model.closeCallInbox(); model.openSettings() }
        compose.runOnIdle { core.sendCall(call.copy(id = "settings")) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
        compose.runOnIdle { model.back() }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
    }

    // D46: a modal file viewer must expose a Call even when the obscured Chat owns its presentation.
    @Test fun threadFileViewerShowsNewCallAndOpensItsDestination() {
        core.terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.THREAD)
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.runOnIdle { core.sendCall(call.copy(id = "older", atMs = 99uL, title = "Earlier review")) }
        compose.runOnIdle {
            core.connection!!.fileDirectory = RemoteDirectory("/repo", listOf(RemoteFileEntry("notes.txt", false)))
            core.connection!!.fileReader = { _, _, _, _ -> RemoteFilePage("Reading the result", 1u, 1u, 1u) }
        }
        compose.onNodeWithTag("agent-actions").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(io.github.shiroel.aries.R.string.menu_open_files)).performClick()
        compose.onNodeWithTag("files-tree").assertIsDisplayed()
        compose.onNodeWithText("notes.txt").performClick()
        compose.onNodeWithTag("files-lines").assertIsDisplayed()
        compose.runOnIdle { core.sendCall(call) }
        compose.onNode(hasTestTag("call_banner") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        compose.onNodeWithTag("files-lines").assertDoesNotExist()
        compose.onNode(hasText(call.title) and hasAnyAncestor(hasTestTag("thread-call-panel"))).assertIsDisplayed()
    }

    // D46: opening a Call from a nested image must close both windows, not reveal Files over it.
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun nestedImageViewerShowsCallAndClosesItsFileBrowser() {
        core.terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.THREAD)
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.runOnIdle {
            val bytes = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\"><rect width=\"16\" height=\"16\" fill=\"red\"/></svg>".toByteArray()
            core.connection!!.fileDirectory = RemoteDirectory("/repo", listOf(RemoteFileEntry("image.svg", false)))
            core.connection!!.fileBytesReader = { _, _, _, _ -> RemoteFileBytes(bytes, 0uL, bytes.size.toULong(), true) }
        }
        compose.onNodeWithTag("agent-actions").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(io.github.shiroel.aries.R.string.menu_open_files)).performClick()
        compose.onNodeWithText("image.svg").performClick()
        val image = hasContentDescription(context.getString(io.github.shiroel.aries.R.string.image_viewer_title))
        compose.waitUntil(10_000) { compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { core.sendCall(call) }
        compose.onNode(hasTestTag("call_banner") and hasAnyAncestor(isDialog() and hasAnyDescendant(image)))
            .assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("thread-call-panel").assertIsDisplayed()
    }

    // D46: sibling task/GitHub windows must both close, even when the caller is already open.
    @Test fun threadCreationAboveGithubShowsNewCallAndOpensItsDestination() {
        core.terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.THREAD)
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.onNodeWithTag("agent-actions").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(io.github.shiroel.aries.R.string.github_button)).performClick()
        compose.onNodeWithTag("github-dialog").assertIsDisplayed()
        compose.runOnIdle { model.beginThreadTask(model.hosts.first()) }
        compose.onNodeWithTag("new-task-dialog").assertIsDisplayed()
        compose.runOnIdle { core.sendCall(call) }
        compose.onNode(hasTestTag("call_banner") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        compose.onNodeWithTag("new-task-dialog").assertDoesNotExist()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("thread-call-panel").assertIsDisplayed()
    }

    // D46: all Host tool windows must expose incoming Calls and close before showing the task.
    @Test fun tasksHostDialogsShowNewCallsAndOpenTheirDestination() {
        core.terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.TASKS)
        for (tool in listOf("files", "preview", "github")) {
            compose.runOnIdle { model.openHost(model.hosts.first()) }
            compose.onNodeWithTag("tasks-host-$tool").performClick()
            compose.runOnIdle { core.sendCall(call.copy(id = tool, title = "Review $tool")) }
            compose.onNode(hasTestTag("call_banner") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
            compose.onNodeWithTag("tasks-detail-tabs").assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
    }

    // D36: landscape/IME must not leave a no-Choice CHECK Call with only a vibration.
    @Test @Config(qualifiers = "h400dp")
    fun compactCheckWithoutChoicesHasAttentionAndInboxCount() {
        core.terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.CONSOLE)
        compose.runOnIdle { core.sendCall(call) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertTextEquals("1")
        compose.onNodeWithTag("call_banner").performClick()
        compose.onNodeWithTag("call-inbox-panel").assertIsDisplayed()
        compose.runOnIdle { model.closeCallInbox(); model.openPushedAgent("h1", "t1", openChat = false) }
        compose.onNodeWithTag("console-inbox-count", useUnmergedTree = true).assertTextEquals("1")
    }

    // D31: another Host's available snapshot supplies the caller name; missing names must not leak raw ids.
    @Test fun callerUsesOtherHostSnapshotOrGenericAgent() {
        core.addHost("Work", Endpoint.Ssh("work.test", 22u, "me"))
        core.storedStates["h2"] = LastKnownState("h2", listOf(TerminalInfo("t1", "Builder", "", emptyList(), null, "live")), 1uL)
        show(AppLayout.CONSOLE)
        compose.runOnIdle { core.sendCall(call.copy(hostId = "h2")) }
        compose.onNodeWithText("Builder on Work", substring = true).assertIsDisplayed()
        compose.runOnIdle { core.sendCall(call.copy(terminal = "raw-terminal-unknown")) }
        compose.onNodeWithText("Agent on Development", substring = true).assertIsDisplayed()
        compose.onNodeWithText("raw-terminal-unknown", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("call_banner").performClick()
        compose.onAllNodes(hasContentDescription("raw-terminal-unknown", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    // D30/D01: suppress duplication only while Chat's panel/pill is rendered; preview/Terminal need attention again.
    @Test fun threadSuppressesOnlyRenderedCallPresentations() {
        core.terminals = listOf(
            TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working),
            TerminalInfo("t2", "Other", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.THREAD)
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.runOnIdle { core.sendCall(call) }
        compose.onNodeWithTag("thread-call-panel").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.runOnIdle { core.sendCall(call.copy(id = "other", terminal = "t2")) }
        compose.onNodeWithTag("thread-waiting-pill").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        val chat = (model.screen as Screen.Chat).chat
        compose.runOnIdle { chat.preview.selected = 5173u }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
        compose.runOnIdle { chat.preview.selected = null }
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.runOnIdle { model.openTerminal(chat.terminal) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
    }

    // D30: a new urgent Call already rendered in the home strip must not also get a banner.
    @Test fun homeStripOwnsVisibleUrgentCalls() {
        show(AppLayout.CONSOLE)
        compose.runOnIdle { core.sendCall(call.copy(urgency = CallUrgency.EMERGENCY)) }
        compose.onNodeWithTag("console-urgent:h1/check").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.runOnIdle { model.openSettings() }
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
    }

    // D34: an arrival can precede its complete feed snapshot; tapping must still open a usable destination.
    @Test fun tasksArrivalBeforeQueueSnapshotOpensInbox() {
        show(AppLayout.TASKS)
        compose.runOnIdle { core.callListeners.getValue("h1").onNewCall(call) }
        compose.onNodeWithTag("call_banner").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-inbox-panel").assertIsDisplayed()
        compose.runOnIdle { core.callListeners.getValue("h1").onCalls(listOf(call)) }
        compose.onNodeWithTag("call-${call.id}").assertIsDisplayed()
    }

    // D30: Tasks' visible Call rows/traces must own attention too; changing to Changes restores the banner.
    @Test fun tasksSuppressesOnlyItsVisibleCallRows() {
        core.terminals = listOf(TerminalInfo("t1", "Current", "", listOf("claude"), "/repo", "live", AgentStatus.Working))
        show(AppLayout.TASKS)
        compose.runOnIdle { core.sendCall(call.copy(choices = listOf("Continue"))) }
        compose.onNodeWithTag("task-call:h1/check").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.runOnIdle { model.openPushedAgent("h1", "t1", openChat = true) }
        compose.onNodeWithTag("tasks-call:h1/check").assertIsDisplayed()
        compose.onNodeWithTag("call_banner").assertDoesNotExist()
        compose.onNodeWithTag("tasks-tab-changes").performClick()
        compose.onNodeWithTag("call_banner").assertIsDisplayed()
    }
}
