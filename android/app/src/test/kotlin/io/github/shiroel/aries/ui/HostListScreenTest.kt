package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Incompatibility
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: the Host list screen against a fake core API. */
@RunWith(RobolectricTestRunner::class)
class HostListScreenTest {
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

    @Test
    fun createsAKeyAndShowsThePublicKeyToCopy() {
        val core = FakeCore()
        show(core)

        compose.onNodeWithText(str(R.string.key_create_button)).performClick()

        compose.onNodeWithText(FakeCore.PUBLIC_KEY).assertExists()
        compose.onNodeWithText(str(R.string.key_copy_button)).assertExists()
    }

    @Test
    fun listsRegisteredHosts() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            addHost("work", Endpoint.Ssh("work.example", 2222u, "dev"))
        }
        show(core)

        compose.onNodeWithText("home").assertExists()
        compose.onNodeWithText("me@10.0.0.2:22").assertExists()
        compose.onNodeWithText("work").assertExists()
        compose.onNodeWithText("dev@work.example:2222").assertExists()
    }

    @Test
    fun addsAHostFromTheForm() {
        val core = FakeCore()
        show(core)

        fun field(label: Int) = compose.onAllNodes(hasSetTextAction() and hasText(str(label))).onFirst()
        field(R.string.host_add_name).performScrollTo().performTextInput("desk")
        field(R.string.host_add_address).performScrollTo().performTextInput("192.168.1.5")
        field(R.string.host_add_user).performScrollTo().performTextInput("shiro")
        compose.onNodeWithText(str(R.string.host_add_button)).performScrollTo().performClick()

        assertEquals(listOf(Endpoint.Ssh("192.168.1.5", 22u, "shiro")), core.hosts().map { it.endpoint })
        compose.onNodeWithText("desk").assertExists()
    }

    @Test
    fun asksToTrustTheHostKeyOnFirstConnectionThenListsTerminals() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            requireTrust = "SHA256:abc"
            terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live"))
        }
        show(core)

        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.host_key_trust_title)).assertExists()
        compose.onNodeWithText(str(R.string.host_key_trust_body, "home", "SHA256:abc")).assertExists()

        compose.onNodeWithText(str(R.string.host_key_trust_button)).performClick()
        compose.waitForIdle()

        assertEquals("SHA256:abc", core.hosts().single().hostKey)
        compose.onNodeWithText(str(R.string.terminals_title, "home")).assertExists()
        compose.onNodeWithText("claude").assertExists()
    }

    @Test
    fun warnsWhenTheHostKeyChanged() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            failWith = CoreException.HostKeyChanged("SHA256:old", "SHA256:new")
        }
        show(core)

        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.host_key_changed_title)).assertExists()
        compose.onNodeWithText(str(R.string.host_key_changed_body, "SHA256:old", "SHA256:new")).assertExists()
    }

    @Test
    fun explainsAnOutdatedHerdrCore() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            failWith = CoreException.Incompatible(Incompatibility.CoreTooOld("0.1.16", "0.1.17"))
        }
        show(core)

        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.incompatible_core_too_old, "0.1.16", "0.1.17")).assertExists()
    }
}
