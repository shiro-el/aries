package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.SavedLaunchTarget
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.LaunchRequest
import io.github.shiroel.aries.core.SavedLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The confirmation's actual buttons must keep execution behind the user's Run tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "h1200dp")
class SavedLaunchEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelNeverRunsTheExternalRequestAndRunApprovesTheReviewedRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val core = FakeCore()
        val host = core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val request = LaunchRequest("printf approved", "/repo", null, null)
        core.savedLaunchesOf[host.id] = mutableListOf(SavedLaunch("1", "Check repo", request))
        val intent = SavedLaunchTarget(host.id, "1").intent(context)
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            LaunchedEffect(Unit) { model.openSavedLaunch(context, intent) }
            AriesApp(model)
        }
        compose.waitForIdle()
        assertTrue(core.connection!!.launched.isEmpty())
        compose.onNodeWithText(context.getString(R.string.cancel_button)).performClick()
        compose.waitForIdle()
        assertTrue(core.connection!!.launched.isEmpty())
        compose.runOnIdle { model.openSavedLaunch(context, intent) }
        compose.onNodeWithText(context.getString(R.string.launch_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(request), core.connection!!.launched)
    }
}
