package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: removing a Host from the Host list, after a confirmation. */
@RunWith(RobolectricTestRunner::class)
class HostRemovalTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
    }

    private fun twoHosts() = FakeCore().apply {
        addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        addHost("work", Endpoint.Ssh("work.example", 22u, "dev"))
    }

    @Test
    fun removesAHostAfterTheUserConfirms() {
        val core = twoHosts()
        show(core)

        compose.openHostActions("h1")
        compose.onNodeWithContentDescription(str(R.string.host_remove_button_label, "home")).performClick()
        compose.onNodeWithText(str(R.string.host_remove_title, "home")).assertExists()
        assertEquals(2, core.hosts().size)

        compose.onNodeWithText(str(R.string.host_remove_confirm_button)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("work"), core.hosts().map { it.name })
        // Removal returns to Settings, whose Connections section is below the initial viewport.
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-host:h2"))
        compose.onNodeWithText("home").assertDoesNotExist()
        compose.onNodeWithText("work").assertExists()
    }

    @Test
    fun keepsTheHostWhenTheUserCancels() {
        val core = twoHosts()
        show(core)

        compose.openHostActions("h1")
        compose.onNodeWithContentDescription(str(R.string.host_remove_button_label, "home")).performClick()
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("home", "work"), core.hosts().map { it.name })
        compose.onNodeWithText(str(R.string.host_remove_title, "home")).assertDoesNotExist()
        compose.onNodeWithText("home").assertExists()
    }
}
