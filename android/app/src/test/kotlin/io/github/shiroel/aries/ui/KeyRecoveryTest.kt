package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class KeyRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)

    @Test fun invalidatedConnectionCanReplaceAKeyAndRegisterItOnTheExistingHost() {
        val core = FakeCore().apply {
            ensureKey()
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            failWith = CoreException.KeyInvalidated()
        }
        val oldKey = core.publicKey()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, keyWork = Dispatchers.Unconfined + inKeyWork.asContextElement(true)) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText(str(R.string.key_invalidated_body)).assertExists()
        compose.onNodeWithText(str(R.string.connect_settings_button)).performClick()
        compose.onNodeWithTag("key-recover-generate-default").performScrollTo().performClick()
        // Cancelling must leave the stored key intact.
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        assertEquals(oldKey, core.publicKey())
        compose.onNodeWithTag("key-recover-generate-default").performClick()
        compose.onNodeWithText(str(R.string.key_recover_replace_button)).performClick()
        compose.waitUntil(5_000) { core.publicKey() != oldKey }
        compose.onNodeWithTag("key-recover-register-h1").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.key_install_password)).performTextInput("one-time password")
        compose.onNodeWithText(str(R.string.key_install_button)).performClick()
        compose.waitUntil(5_000) { core.passwordsTried.any { it.first == "h1" } }
        assertEquals(1, core.hosts().size)
        assertEquals(emptyList<String>(), core.offKeyWork)
    }
}
