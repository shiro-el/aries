package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun show(core: io.github.shiroel.aries.core.AriesCoreInterface) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
    }

    private fun toggle() = compose.onNodeWithText(str(R.string.key_biometric_label))

    // Regression D88: auth rejection must display/copy the assigned key, even with a different default key.
    @Test fun rejectedHostShowsItsAssignedKeyAndCopiesThatPublicKey() {
        val fake = FakeCore().apply { ensureKey() }
        val host = fake.addHost("Office", Endpoint.Ssh("office", 22u, "me"))
        val assigned = fake.createKey("Office key")
        fake.setHostSshKey(host.id, assigned.id)
        fake.failWith = CoreException.AuthRejected()
        val core = object : io.github.shiroel.aries.core.AriesCoreInterface by fake {
            override fun keys() = fake.keys().map {
                if (it.id == assigned.id) it.copy(openssh = "ssh-ed25519 assigned-public-key") else it
            }
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("Office").performClick()
        compose.onNodeWithText("Office key").assertExists()
        compose.onNodeWithText(str(R.string.key_fingerprint, assigned.fingerprint)).assertExists()
        compose.onNodeWithText(str(R.string.key_copy_button)).performClick()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("ssh-ed25519 assigned-public-key", clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText(str(R.string.key_qr_button)).performScrollTo().performClick()
        compose.runOnIdle {
            val qr = (model.prompt as Prompt.KeyQr).qr
            val side = qr.size.toInt()
            val pixels = Array(side) { y -> BooleanArray(side) { x -> qr.modules[y * side + x] } }
            assertEquals("ssh-ed25519 assigned-public-key", com.google.zxing.qrcode.decoder.Decoder().decode(pixels).text)
        }
    }

    @Test
    fun requiresAndReleasesBiometricUnlock() {
        val core = FakeCore().apply { ensureKey() }
        show(core)
        compose.openKeyDetails()
        toggle().assertIsOff()

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
        compose.openKeyDetails()

        toggle().assertIsOn()
    }

    @Test
    fun explainsWhenBiometricUnlockIsUnavailable() {
        val core = FakeCore().apply {
            ensureKey()
            failProtectionWith = CoreException.BiometricUnavailable("no fingerprint enrolled")
        }
        show(core)
        compose.openKeyDetails()

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
