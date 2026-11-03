package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.JumpHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: registering, editing and trusting jump hosts against a fake core API. */
@RunWith(RobolectricTestRunner::class)
class JumpHostsScreenTest {
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

    private fun field(label: Int) = compose.onAllNodes(hasSetTextAction() and hasText(str(label)))

    @Test
    fun createsAnotherKeyWithALabel() {
        val core = FakeCore().apply { ensureKey() }
        show(core)

        field(R.string.key_new_label).onFirst().performScrollTo().performTextInput("bastion")
        compose.onNodeWithText(str(R.string.key_new_button)).performScrollTo().performClick()

        assertEquals(listOf("", "bastion"), core.keys().map { it.label })
        compose.onNodeWithText("bastion").assertExists()
    }

    @Test
    fun registersTwoJumpHostsWithAKeyEachThenEditsThem() {
        val core = FakeCore().apply {
            ensureKey()
            createKey("outer")
            addHost("work", Endpoint.Ssh("10.1.0.5", 22u, "me"))
        }
        val outer = core.keys()[1].id
        show(core)

        compose.onNodeWithText(str(R.string.host_jumps_button)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.jumps_title, "work")).assertExists()
        compose.onNodeWithText(str(R.string.jumps_empty)).assertExists()

        compose.onNodeWithText(str(R.string.jumps_add_button)).performScrollTo().performClick()
        field(R.string.host_add_address).onFirst().performTextInput("bastion.example")
        field(R.string.host_add_user).onFirst().performTextInput("jump")
        // Pick the "outer" key for the first hop.
        compose.onAllNodesWithText(str(R.string.jumps_key_label, str(R.string.key_default_label))).onFirst()
            .performScrollTo().performClick()
        compose.onNodeWithText("outer").performClick()

        compose.onNodeWithText(str(R.string.jumps_add_button)).performScrollTo().performClick()
        field(R.string.host_add_address)[1].performScrollTo().performTextInput("10.0.0.1")
        field(R.string.host_add_port)[1].performScrollTo().performTextReplacement("2222")
        field(R.string.host_add_user)[1].performScrollTo().performTextInput("ops")

        compose.onNodeWithText(str(R.string.jumps_save_button)).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(
                JumpHost("bastion.example", 22u, "jump", outer, null),
                JumpHost("10.0.0.1", 2222u, "ops", null, null),
            ),
            core.jumpHosts("h1"),
        )
        compose.onNodeWithText(str(R.string.host_jumps_summary, "bastion.example → 10.0.0.1")).assertExists()

        // Edit: remove the first hop.
        compose.onNodeWithText(str(R.string.host_jumps_button)).performScrollTo().performClick()
        compose.onAllNodesWithText(str(R.string.jumps_remove_button)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.jumps_save_button)).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf(JumpHost("10.0.0.1", 2222u, "ops", null, null)), core.jumpHosts("h1"))
    }

    @Test
    fun asksToTrustEachJumpHostOnTheWayThenConnects() {
        val core = FakeCore().apply {
            addHost("work", Endpoint.Ssh("10.1.0.5", 22u, "me"))
            setJumpHosts("h1", listOf(JumpHost("bastion.example", 22u, "jump", null, null)))
            requireJumpTrust = "SHA256:jjj"
        }
        show(core)

        compose.onNodeWithText("work").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.jump_key_trust_title)).assertExists()
        compose.onNodeWithText(str(R.string.jump_key_trust_body, 1, "bastion.example", "work", "SHA256:jjj"))
            .assertExists()

        compose.onNodeWithText(str(R.string.host_key_trust_button)).performClick()
        compose.waitForIdle()

        assertEquals("SHA256:jjj", core.jumpHosts("h1").single().hostKey)
        compose.onNodeWithText(str(R.string.terminals_title, "work")).assertExists()
    }

    @Test
    fun warnsWhenAJumpHostKeyChanged() {
        val core = FakeCore().apply {
            addHost("work", Endpoint.Ssh("10.1.0.5", 22u, "me"))
            failWith = CoreException.JumpHostKeyChanged(1u, "inner", "SHA256:old", "SHA256:new")
        }
        show(core)

        compose.onNodeWithText("work").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.jump_key_changed_title)).assertExists()
        compose.onNodeWithText(str(R.string.jump_key_changed_body, 2, "inner", "SHA256:old", "SHA256:new"))
            .assertExists()
    }

    @Test
    fun namesTheJumpHostThatRejectedItsKey() {
        val core = FakeCore().apply {
            addHost("work", Endpoint.Ssh("10.1.0.5", 22u, "me"))
            failWith = CoreException.JumpAuthRejected(0u, "bastion.example")
        }
        show(core)

        compose.onNodeWithText("work").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.connect_jump_auth_rejected, 1, "bastion.example")).assertExists()
    }
}
