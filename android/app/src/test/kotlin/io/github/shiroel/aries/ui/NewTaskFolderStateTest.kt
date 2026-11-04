package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D56: existing folder selection tests do not recreate the UI while browsing a nested folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "h1200dp")
class NewTaskFolderStateTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun restoreFolder(layout: AppLayout) {
        val core = FakeCore().apply { ensureKey() }
        val host = core.addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
        // Like the Activity ViewModel, the model and its scope survive configuration changes.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val model = HostsModel(core, scope)
        val settings = ThemeSettings(context.getSharedPreferences("folder-restore", Context.MODE_PRIVATE)).apply {
            chooseLayout(layout)
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AriesApp(model, settings) }
        try {
            compose.runOnIdle { model.connect(host) }
            compose.waitForIdle()
            compose.runOnIdle {
                core.connection!!.fileDirectoryReader = { _, path ->
                    val cwd = path ?: "/repo"
                    RemoteDirectory(cwd, when (cwd) {
                        "/repo" -> listOf(RemoteFileEntry("src", true))
                        "/repo/src" -> listOf(RemoteFileEntry("nested", true))
                        else -> emptyList()
                    })
                }
                model.prepareTask((model.screen as Screen.Browse).browser.link!!, "/repo")
            }
            if (layout == AppLayout.THREAD) {
                compose.onNodeWithContentDescription(context.getString(R.string.new_task_picker_label,
                    context.getString(R.string.launch_cwd), "/repo")).performClick()
            }
            compose.onNodeWithText(context.getString(R.string.new_task_browse)).performClick()
            compose.onNodeWithText("src").performClick()
            compose.onNodeWithText("nested").performClick()
            compose.onNodeWithText("/repo/src/nested").assertExists()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("/repo/src/nested").assertExists()
            compose.onNodeWithText(context.getString(R.string.new_task_use_folder)).performClick()
            if (layout == AppLayout.THREAD) {
                compose.onNodeWithContentDescription(context.getString(R.string.new_task_picker_label,
                    context.getString(R.string.launch_cwd), "/repo/src/nested")).assertExists()
            } else compose.onNodeWithTag("launch_cwd").assertTextContains("/repo/src/nested")
        } finally {
            compose.runOnIdle { scope.cancel() }
        }
    }

    @Test fun tasksFolderBrowserKeepsItsNestedLocationAcrossRecreation() = restoreFolder(AppLayout.TASKS)
    @Test fun threadFolderBrowserKeepsItsNestedLocationAcrossRecreation() = restoreFolder(AppLayout.THREAD)
}
