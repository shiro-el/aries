package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: delayed core calls through the settings UI's public state/actions. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class HostSettingsWorkTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: HostsModel
    private fun show(core: AriesCoreInterface, host: Host, platform: PushPlatform = NoPush) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope, pushPlatform = platform) }
            MaterialTheme { AriesApp(model) }
        }
        compose.runOnIdle { model.openHostSettings(host) }
        compose.waitForIdle()
    }

    @Test fun failedBeaconReinstallKeepsTheInstalledState() {
        val fake = FakeCore().apply { beacon = installed("0.1.0") }
        val host = fake.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val gate = CompletableDeferred<Unit>()
        val core = object : AriesCoreInterface by fake {
            override suspend fun installBeacon(hostId: String, fetcher: BeaconFetcher): BeaconInstall {
                gate.await()
                throw CoreException.KeyLocked()
            }
        }
        show(core, host)
        compose.runOnIdle { model.beacon.install() }
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("A failed reinstall must preserve the last installed state", model.beacon.installed) }
    }
    @Test fun reopeningTheHostKeepsAllPendingSettingsOperationsAndTheirResults() {
        val fake = FakeCore().apply { herdrState = HerdrCoreState.Missing }
        val home = fake.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val other = fake.addHost("work", Endpoint.Ssh("work", 22u, "me"))
        val gate = CompletableDeferred<Unit>()
        var beaconAttempts = 0
        var pushAttempts = 0
        var herdrAttempts = 0
        val core = object : AriesCoreInterface by fake {
            override suspend fun installBeacon(hostId: String, fetcher: BeaconFetcher): BeaconInstall {
                beaconAttempts++
                gate.await()
                return fake.installBeacon(hostId, fetcher)
            }
            override suspend fun installHerdrCore(hostId: String, fetcher: BeaconFetcher, listener: HerdrInstallListener): HerdrCoreState {
                herdrAttempts++
                gate.await()
                return fake.installHerdrCore(hostId, fetcher, listener)
            }
            override suspend fun enablePush(hostId: String, relayUrl: String, device: PushDevice, http: HttpClient, progress: PushProgress): PushPairing {
                pushAttempts++
                gate.await()
                return fake.enablePush(hostId, relayUrl, device, http, progress)
            }
        }
        val platform = object : PushPlatform {
            override val relayUrl = "https://relay.example"
            override suspend fun allowNotifications() = true
            override suspend fun deviceToken() = "test-token"
        }
        show(core, home, platform)
        compose.runOnIdle {
            model.beacon.install()
            model.herdrInstall.askInstall()
            model.herdrInstall.install()
            model.push.enable()
        }
        compose.runOnIdle {
            model.settingsBack()
            model.openHostSettings(other)
            model.openHostSettings(home)
            model.beacon.install()
            model.herdrInstall.askInstall()
            model.herdrInstall.install()
            model.push.enable()
        }
        compose.runOnIdle {
            assertEquals("Beacon reinstall must not run twice", 1, beaconAttempts)
            assertEquals("Herdr installer must not run twice", 1, herdrAttempts)
            assertEquals("Pairing must not run twice", 1, pushAttempts)
            gate.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(model.beacon.installed)
            assertTrue(model.herdrInstall.state is HerdrCoreState.Installed)
            assertTrue(model.push.ui is PushUi.On)
        }
    }

    @Test fun pushFailureBeforeItsFirstProgressCallbackCanBeRetried() {
        val fake = FakeCore().apply { beacon = installed("0.1.0") }
        val host = fake.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        var fail = true
        val core = object : AriesCoreInterface by fake {
            override suspend fun enablePush(hostId: String, relayUrl: String, device: PushDevice, http: HttpClient, progress: PushProgress): PushPairing {
                if (fail) throw CoreException.KeyLocked()
                return fake.enablePush(hostId, relayUrl, device, http, progress)
            }
        }
        val platform = object : PushPlatform {
            override val relayUrl = "https://relay.example"
            override suspend fun allowNotifications() = true
            override suspend fun deviceToken() = "test-token"
        }
        show(core, host, platform)
        compose.runOnIdle { model.push.enable() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("An early failure must return to Off with a retry", model.push.ui is PushUi.Off)
            assertNotNull(model.push.problem)
            fail = false
            model.push.enable()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.push.ui is PushUi.On) }
    }

    @Test fun sendingATestRejectsDuplicateTapsAndExpiresTheSuccessNotice() {
        val fake = FakeCore().apply { pushPaired["h1"] = "https://relay.example" }
        val host = fake.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val core = object : AriesCoreInterface by fake {
            override suspend fun sendTestPush(hostId: String) {
                attempts++
                gate.await()
            }
        }
        show(core, host)
        compose.runOnIdle {
            model.push.sendTest()
            model.push.sendTest()
        }
        compose.runOnIdle {
            assertEquals("Only one test may be sent while the first is pending", 1, attempts)
            gate.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertNotNull(model.push.notice) }
        compose.mainClock.advanceTimeBy(6_000)
        compose.runOnIdle { assertNull(model.push.notice) }
    }

    @Test fun aTestNotificationTimeoutAllowsAnotherAttempt() {
        val fake = FakeCore().apply { pushPaired["h1"] = "https://relay.example" }
        val host = fake.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        var attempts = 0
        val core = object : AriesCoreInterface by fake {
            override suspend fun sendTestPush(hostId: String) {
                attempts++
                if (attempts == 1) CompletableDeferred<Unit>().await()
            }
        }
        show(core, host)
        compose.runOnIdle { model.push.sendTest() }
        compose.mainClock.advanceTimeBy(31_000)
        compose.runOnIdle {
            assertFalse(model.push.busy)
            assertNotNull(model.push.problem)
            model.push.sendTest()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertNotNull(model.push.notice)
            assertNull(model.push.problem)
        }
    }

    @Test fun permissionExceptionsAndCancellationAlsoLeavePushRetryable() {
        val fake = FakeCore().apply { beacon = installed("0.1.0") }
        val host = fake.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        var failure: Exception? = IllegalStateException("permission unavailable")
        val platform = object : PushPlatform {
            override val relayUrl = "https://relay.example"
            override suspend fun allowNotifications(): Boolean {
                failure?.let { throw it }
                return true
            }
            override suspend fun deviceToken() = "test-token"
        }
        show(fake, host, platform)
        for (exception in listOf(IllegalStateException("permission unavailable"), kotlinx.coroutines.CancellationException("cancelled"))) {
            compose.runOnIdle { failure = exception; model.push.enable() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(model.push.ui is PushUi.Off)
                assertFalse(model.push.busy)
                assertNotNull(model.push.problem)
            }
        }
        compose.runOnIdle { failure = null; model.push.enable() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.push.ui is PushUi.On) }
    }

}
