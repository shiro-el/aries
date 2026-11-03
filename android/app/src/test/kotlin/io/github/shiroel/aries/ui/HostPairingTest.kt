package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostPairingDetails
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class HostPairingTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = context.getString(id)
    private val details = HostPairingDetails("dev.tailnet", 2222u, "dev", "SHA256:host-pin", 9999999999u)

    private fun show(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { QrHostPairing(model, scanQr = { scanned -> scanned("ephemeral QR credential") }) }
        }
    }

    @Test fun scanPrefillsReviewAndOnlyConfirmationRegistersWithPinnedHostKey() {
        val core = FakeCore().apply { ensureKey(); pairingDetails = details }
        show(core)
        compose.onNodeWithTag("host-pair-scan").performClick()
        compose.onNodeWithTag("host-pair-address").assertTextContains("dev.tailnet")
        compose.onNodeWithTag("host-pair-port").assertTextContains("2222")
        compose.onNodeWithTag("host-pair-user").assertTextContains("dev")
        compose.onNodeWithText("SHA256:host-pin", substring = true).assertExists()
        assertTrue(core.hosts().isEmpty())
        compose.onNodeWithTag("host-pair-confirm").performClick()
        compose.waitForIdle()
        assertEquals("SHA256:host-pin", core.hosts().single().hostKey)
        assertEquals(1, core.pairingRequests.size)
        compose.onNodeWithTag("host-pair-confirm").assertDoesNotExist()
    }

    @Test fun expiredScanHasAnErrorAndCannotRegister() {
        val core = FakeCore().apply { ensureKey(); pairingScanError = CoreException.InvalidPairingQr() }
        show(core)
        compose.onNodeWithTag("host-pair-scan").performClick()
        compose.onNodeWithText(str(R.string.host_pair_invalid)).assertExists()
        compose.onNodeWithTag("host-pair-confirm").assertDoesNotExist()
        assertTrue(core.hosts().isEmpty())
    }

    @Test fun consumedTokenKeepsReviewAndShowsFailureWithoutSavingAHost() {
        val core = FakeCore().apply { ensureKey(); pairingDetails = details; pairingError = CoreException.PairingRefused() }
        show(core)
        compose.onNodeWithTag("host-pair-scan").performClick()
        compose.onNodeWithTag("host-pair-confirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.host_pair_refused)).assertExists()
        assertTrue(core.hosts().isEmpty())
    }
}
