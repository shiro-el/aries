package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.HerdrCoreState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HerdrInstallSectionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun show(core: FakeCore) {
        val host = core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, keyWork = Dispatchers.Unconfined + inKeyWork.asContextElement(true)) }
            MaterialTheme { AriesApp(model) }
        }
        compose.openHostActions(host.id)
        compose.waitForIdle()
        compose.onNodeWithTag("host-actions:${host.id}").performScrollToNode(hasText(str(R.string.herdr_install_title)))
    }
    private fun click(id: Int) {
        val node = compose.onNodeWithText(str(id))
        if (id != R.string.cancel_button && id != R.string.herdr_install_confirm) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    @Test fun missingCoreRequiresApprovalAndCancelDoesNothing() {
        val core = FakeCore().apply { herdrState = HerdrCoreState.Missing }
        show(core)
        click(R.string.herdr_install_button)
        System.getenv("ARIES_SETTINGS_RENDER_DIR")?.let { directory ->
            val file = java.io.File(directory, "herdr-consent.png")
            file.parentFile?.mkdirs()
            file.outputStream().use {
                compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertEquals(0, core.herdrInstalls)
        click(R.string.cancel_button)
        assertEquals(0, core.herdrInstalls)
        click(R.string.herdr_install_button)
        click(R.string.herdr_install_confirm)
        compose.onNodeWithText(str(R.string.herdr_install_ready)).assertExists()
        assertEquals(1, core.herdrInstalls)
        assertEquals(emptyList<String>(), core.offKeyWork)
    }

    @Test fun updateShowsProgressAndRetainsOutput() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val core = FakeCore().apply {
            herdrState = HerdrCoreState.Installed("0.1.16", true)
            herdrGate = gate
        }
        show(core)
        click(R.string.herdr_install_update)
        click(R.string.herdr_install_confirm)
        compose.onNodeWithText(str(R.string.herdr_install_download)).assertExists()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("herdr-advanced").performScrollTo().performClick()
        compose.onNodeWithText("hc 0.1.17", substring = true).performScrollTo().assertExists()
    }

    @Test fun checksumFailureExplainsRecoveryAndAllowsRetry() {
        val core = FakeCore().apply {
            herdrState = HerdrCoreState.Missing
            herdrFailWith = CoreException.HerdrChecksum("aa", "bb")
        }
        show(core)
        click(R.string.herdr_install_button)
        click(R.string.herdr_install_confirm)
        compose.onNodeWithText(str(R.string.herdr_install_checksum, "aa", "bb")).assertExists()
        assertEquals(HerdrCoreState.Missing, core.herdrState)
        compose.runOnIdle { core.herdrFailWith = null }
        click(R.string.herdr_install_button)
        click(R.string.herdr_install_confirm)
        compose.onNodeWithText(str(R.string.herdr_install_ready)).assertExists()
        assertEquals(2, core.herdrInstalls)
    }

    @Test fun anOlderCoreConnectionFailureOpensTheInstallerWithoutInstalling() {
        val core = FakeCore().apply {
            herdrState = HerdrCoreState.Installed("0.1.16", true)
            failWith = CoreException.Incompatible(io.github.shiroel.aries.core.Incompatibility.CoreTooOld("0.1.16", "0.1.17"))
        }
        val host = core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, keyWork = Dispatchers.Unconfined + inKeyWork.asContextElement(true)) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("box").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.herdr_install_update)).performScrollTo().assertExists()
        assertEquals(0, core.herdrInstalls)
    }

    @Test fun aMissingCoreConnectionOpensSettingsWithoutAutomaticallyInstalling() {
        val core = FakeCore().apply {
            herdrState = HerdrCoreState.Missing
            failWith = CoreException.Protocol("herdr-core connect ended before answering hello")
        }
        core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("box").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.herdr_install_button)).performScrollTo().assertExists()
        assertEquals(0, core.herdrInstalls)
    }

    @Test fun aNewerCoreDoesNotOfferADowngrade() {
        val core = FakeCore().apply { herdrState = HerdrCoreState.Installed("0.2.1", false) }
        show(core)
        compose.onNodeWithTag("herdr-advanced").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.herdr_install_version, "0.2.1")).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.herdr_install_button)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.herdr_install_update)).assertDoesNotExist()
        assertEquals(0, core.herdrInstalls)
    }

    @Test fun anOlderRunningCoreWithACurrentBinaryKeepsTheExistingRecoveryMessage() {
        val core = FakeCore().apply {
            herdrState = HerdrCoreState.Installed("0.1.17", false)
            failWith = CoreException.Incompatible(io.github.shiroel.aries.core.Incompatibility.CoreTooOld("0.1.16", "0.1.17"))
        }
        core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("box").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.incompatible_core_too_old, "0.1.16", "0.1.17")).assertExists()
        assertEquals(0, core.herdrInstalls)
    }
}
