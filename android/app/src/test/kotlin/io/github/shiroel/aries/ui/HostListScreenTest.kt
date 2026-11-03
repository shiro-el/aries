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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
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
import org.robolectric.annotation.Config

/** Seam 3: the Host list screen against a fake core API. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
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
    fun hostsKeepOnlyTheAddFormCollapsedUntilOpened() {
        val core = FakeCore().apply { ensureKey() }
        show(core)
        compose.onNodeWithText(FakeCore.PUBLIC_KEY).assertDoesNotExist()
        compose.onNodeWithTag("hosts-key-band").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText(str(R.string.host_add_address))).assertDoesNotExist()
        compose.onNodeWithTag("hosts-add-toggle").performClick()
        compose.onNode(hasSetTextAction() and hasText(str(R.string.host_add_address))).assertExists()
    }

    @Test
    fun createsAKeyInSettingsAndShowsThePublicKeyToCopy() {
        show(FakeCore())
        compose.openKeyDetails()
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
        val core = FakeCore().apply { ensureKey() }
        show(core)

        compose.openHostAddForm()
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

    private fun installWithPassword(password: String) {
        compose.openHostActionsIfClosed("h1")
        compose.onNodeWithText(str(R.string.host_install_key_button)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(str(R.string.key_install_password))).performTextInput(password)
        compose.onNodeWithText(str(R.string.key_install_button)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun installsTheKeyWithAOneTimePassword() {
        val core = FakeCore().apply { addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me")) }
        show(core)

        installWithPassword("hunter2")

        assertEquals(listOf("h1" to "hunter2"), core.passwordsTried)
        compose.onNodeWithText(str(R.string.key_install_added_body, "home")).assertExists()
    }

    @Test
    fun saysWhenTheKeyWasAlreadyInstalled() {
        val core = FakeCore().apply { addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me")) }
        show(core)

        installWithPassword("hunter2")
        compose.onNodeWithText(str(R.string.ok_button)).performClick()
        installWithPassword("hunter2")

        compose.onNodeWithText(str(R.string.key_install_present_body, "home")).assertExists()
    }

    @Test
    fun asksToTrustTheHostKeyBeforeSendingThePassword() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            requireTrust = "SHA256:abc"
        }
        show(core)

        installWithPassword("hunter2")
        assertEquals(emptyList<Pair<String, String>>(), core.passwordsTried)
        compose.onNodeWithText(str(R.string.host_key_trust_body, "home", "SHA256:abc")).assertExists()

        compose.onNodeWithText(str(R.string.host_key_trust_button)).performClick()
        compose.waitForIdle()

        // Trusting asks for the password again: it was not kept.
        compose.onNode(hasSetTextAction() and hasText(str(R.string.key_install_password))).performTextInput("hunter2")
        compose.onNodeWithText(str(R.string.key_install_button)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("h1" to "hunter2"), core.passwordsTried)
        compose.onNodeWithText(str(R.string.key_install_added_body, "home")).assertExists()
    }

    @Test
    fun explainsAWrongPassword() {
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            installFailWith = CoreException.PasswordRejected()
        }
        show(core)

        installWithPassword("wrong")

        compose.onNodeWithText(str(R.string.key_install_failed_title)).assertExists()
        compose.onNodeWithText(str(R.string.key_install_password_rejected)).assertExists()
    }

    @Test
    fun explainsAPermissionProblemOnTheHost() {
        val detail = "chmod: /home/me/.ssh: Operation not permitted"
        val core = FakeCore().apply {
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            installFailWith = CoreException.KeyInstallFailed(detail)
        }
        show(core)

        installWithPassword("hunter2")

        compose.onNodeWithText(str(R.string.key_install_write_failed, detail)).assertExists()
    }

    @Test
    fun showsThePublicKeyAsAQrCode() {
        val core = FakeCore().apply { ensureKey() }
        show(core)

        compose.openKeyDetails()
        compose.onNodeWithText(str(R.string.key_qr_button)).performScrollTo().performClick()

        compose.onNodeWithText(str(R.string.key_qr_title)).assertExists()
        compose.onNodeWithTag("key_qr").assertExists()
    }
}
