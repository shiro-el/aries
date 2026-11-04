package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D01: the Console recovery test cannot catch missing controls or draft recreation on other surfaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "h1200dp")
class NewTaskRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var model: HostsModel

    private fun recover(layout: AppLayout, fullScreen: Boolean) {
        val core = FakeCore().apply {
            ensureKey()
            addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
            terminals = emptyList()
        }
        val settings = ThemeSettings(context.getSharedPreferences("task-recovery", Context.MODE_PRIVATE)).apply {
            chooseLayout(layout)
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            LaunchedEffect(Unit) { model.start(layout = layout) }
            AriesApp(model, settings)
        }
        compose.waitForIdle()
        if (layout == AppLayout.TASKS) compose.onNodeWithTag("tasks-new-task").performClick()
        if (fullScreen) compose.runOnIdle { model.prepareTask(model.task!!.link) }
        val promptTag = if (layout == AppLayout.TASKS && !fullScreen) "tasks-new-prompt" else "new-task-prompt"
        val startTag = if (layout == AppLayout.TASKS && !fullScreen) "tasks-new-start" else "thread-task-start"
        fun node(tag: String) = compose.onNode(hasTestTag(tag) and
            if (fullScreen) hasAnyAncestor(isDialog()) else !hasAnyAncestor(isDialog()))
        node(promptTag).performTextInput("Uncertain task")
        val reply = CompletableDeferred<Unit>()
        val connection = core.connection!!
        compose.runOnIdle {
            connection.launchReplyHold = reply
            connection.launchReplyFailure = CoreException.Closed()
        }
        node(startTag).performClick()
        node(startTag).assertIsNotEnabled()
        compose.runOnIdle { reply.complete(Unit) }
        val stop = compose.onNode(hasText(context.getString(R.string.launch_stop_tracking_button)) and
            if (fullScreen) hasAnyAncestor(isDialog()) else !hasAnyAncestor(isDialog()))
        if (fullScreen || layout == AppLayout.TASKS) stop.performScrollTo()
        stop.performClick()
        if (!fullScreen) {
            node(promptTag).performTextInput("Fresh inline draft")
            node(startTag).assertIsEnabled()
        }
        // Reopening New Task must not recover the retired request, and Start becomes usable again.
        compose.runOnIdle {
            val link = (model.screen as Screen.Agents).board.links.getValue("h1")
            model.prepareTask(link, showDialog = fullScreen)
        }
        node(promptTag).performTextReplacement("Different work")
        node(startTag).assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, connection.launched.size)
            assertEquals(emptyList<Pair<String, String>>(), connection.typed)
        }
    }

    @Test fun threadInlineCanRetireAnUncertainLaunch() = recover(AppLayout.THREAD, false)
    @Test fun threadFullScreenCanRetireAnUncertainLaunch() = recover(AppLayout.THREAD, true)
    @Test fun tasksInlineCanRetireAnUncertainLaunch() = recover(AppLayout.TASKS, false)
}
