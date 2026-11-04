package io.github.shiroel.aries.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostSettingsPairingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pairedPushStaysUsableDuringAFailedBeaconCheckAndBeaconCanBeCheckedAgain() {
        val fake = FakeCore().apply {
            beaconSkills = listOf(BeaconSkillRoot("/home/me/.agents/skills/aries-call-human", BeaconSkillOutcome.INSTALLED))
            beacon = installed("0.1.0")
            pushPaired["h1"] = "https://relay.example"
        }
        val home = fake.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val gate = CompletableDeferred<Unit>()
        var first = true
        val core = object : AriesCoreInterface by fake {
            override suspend fun checkBeacon(hostId: String, fetcher: BeaconFetcher): BeaconCheck {
                if (first) {
                    first = false
                    gate.await()
                    throw CoreException.KeyLocked()
                }
                return fake.checkBeacon(hostId, fetcher)
            }
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.runOnIdle { model.openHostSettings(home) }
        compose.onNodeWithText("Push is on").performScrollTo().assertIsDisplayed()
        screenshot("beacon-checking-push-on")
        compose.onNodeWithTag("push-advanced").performScrollTo().performClick()
        compose.onNodeWithText("Send a test notification").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, fake.testPushes)
            gate.complete(Unit)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Check Beacon again").performScrollTo().assertIsDisplayed()
        screenshot("beacon-unavailable-push-on")
        compose.onNodeWithText("Check Beacon again").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.beacon.installed) }
        screenshot("beacon-recovered")
        compose.onNodeWithTag("push-advanced").performScrollTo().performClick()
        compose.onNodeWithText("Herdr Core").performScrollTo()
        screenshot("healthy-components")
    }

    private fun screenshot(name: String) {
        System.getenv("ARIES_SETTINGS_RENDER_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
