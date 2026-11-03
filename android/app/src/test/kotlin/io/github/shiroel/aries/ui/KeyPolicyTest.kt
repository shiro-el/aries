package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.KeyProtection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: choosing whether the SSH key needs a biometric unlock (ticket 23). */
@RunWith(RobolectricTestRunner::class)
class KeyPolicyTest {
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

    private fun toggle() = compose.onNodeWithText(str(R.string.key_biometric_label))

    @Test
    fun requiresAndReleasesBiometricUnlock() {
        val core = FakeCore().apply { ensureKey() }
        show(core)
        toggle().assertIsOff()
        compose.onNodeWithText(str(R.string.key_biometric_hint)).assertExists()

        toggle().performClick()
        compose.waitForIdle()
        assertEquals(KeyProtection.BIOMETRIC, core.keyProtection())
        toggle().assertIsOn()

        toggle().performClick()
        compose.waitForIdle()
        assertEquals(KeyProtection.PLAIN, core.keyProtection())
        toggle().assertIsOff()
    }

    @Test
    fun startsFromTheStoredPolicy() {
        val core = FakeCore().apply {
            ensureKey()
            setKeyProtection(KeyProtection.BIOMETRIC)
        }
        show(core)

        toggle().assertIsOn()
    }

    @Test
    fun explainsWhenBiometricUnlockIsUnavailable() {
        val core = FakeCore().apply {
            ensureKey()
            failProtectionWith = CoreException.BiometricUnavailable("no fingerprint enrolled")
        }
        show(core)

        toggle().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.key_biometric_unavailable_body)).assertExists()
        assertEquals(KeyProtection.PLAIN, core.keyProtection())
        toggle().assertIsOff()
    }

    @Test
    fun explainsALockedKeyWhenConnecting() {
        val core = FakeCore().apply {
            ensureKey()
            addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
            failWith = CoreException.KeyLocked()
        }
        show(core)

        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.key_locked_body)).assertExists()
    }
}
