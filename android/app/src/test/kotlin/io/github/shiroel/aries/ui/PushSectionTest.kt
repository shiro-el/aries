package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.FetchException
import io.github.shiroel.aries.core.HttpClient
import io.github.shiroel.aries.core.HttpRequest
import io.github.shiroel.aries.core.HttpResponse
import io.github.shiroel.aries.core.PushStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: the push section of a connected Host against a fake core API. */
@RunWith(RobolectricTestRunner::class)
class PushSectionTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val noDownload = object : BeaconFetcher {
        override fun fetch(url: String): ByteArray = error("the fake core does not download")
    }
    private val noHttp = object : HttpClient {
        override fun send(request: HttpRequest): HttpResponse = throw FetchException.Failed("the fake core does not call HTTP")
    }

    private class Platform(
        override val relayUrl: String? = RELAY,
        var allowed: Boolean = true,
        var token: String? = "fcm-token",
    ) : PushPlatform {
        var asked = 0
        override suspend fun deviceToken(): String = token ?: throw PushUnavailable("no Google Play services")
        override suspend fun allowNotifications(): Boolean {
            asked++
            return allowed
        }
    }

    private fun installed() = BeaconState.Installed("0.1.0", 1u, true, "0.1.17", true, true, ">=0.1.0, <0.2.0")

    private fun connectTo(core: FakeCore, platform: PushPlatform = Platform()): HostsModel {
        core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope, noDownload, platform, noHttp) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("box").performClick()
        compose.waitForIdle()
        return model
    }

    private fun click(res: Int) {
        compose.onNodeWithText(str(res)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun shows(text: String) {
        compose.onNodeWithText(text).performScrollTo().assertExists()
    }

    private fun step(format: Int, step: Int) = str(format, str(step))

    @Test
    fun asksForTheBeaconFirst() {
        connectTo(FakeCore())
        shows(str(R.string.push_needs_beacon))
    }

    @Test
    fun turnsPushOnAndSaysATestIsOnItsWay() {
        val core = FakeCore().apply { beacon = installed() }
        val platform = Platform()
        connectTo(core, platform)
        shows(str(R.string.push_force_stop_note))

        click(R.string.push_enable_button)

        assertEquals(1, platform.asked)
        assertEquals(RELAY, core.pushPaired.values.single())
        assertEquals(1, core.testPushes)
        shows(str(R.string.push_on_body, RELAY))
        shows(str(R.string.push_test_sent))
    }

    @Test
    fun showsWhichStepFailedAndWhy() {
        val core = FakeCore().apply { beacon = installed(); pushFailAt = PushStep.PAIR }
        connectTo(core)

        click(R.string.push_enable_button)

        shows(step(R.string.push_step_done, R.string.push_step_register))
        shows(step(R.string.push_step_failed, R.string.push_step_pair))
        shows(str(R.string.push_failed, str(R.string.push_step_pair), "relay said no"))
        assertTrue(core.pushPaired.isEmpty())

        core.pushFailAt = null
        click(R.string.push_retry_button)
        shows(str(R.string.push_on_body, RELAY))
    }

    @Test
    fun aFailedTestLeavesPushOnWithTheReason() {
        val core = FakeCore().apply { beacon = installed(); pushFailAt = PushStep.TEST }
        connectTo(core)

        click(R.string.push_enable_button)

        shows(str(R.string.push_on_body, RELAY))
        shows(str(R.string.push_failed, str(R.string.push_step_test), "relay said no"))
    }

    @Test
    fun doesNotTurnOnWhenNotificationsAreDenied() {
        val core = FakeCore().apply { beacon = installed() }
        connectTo(core, Platform(allowed = false))

        click(R.string.push_enable_button)

        shows(str(R.string.push_permission_denied))
        assertTrue(core.pushPaired.isEmpty())
    }

    @Test
    fun explainsAPhoneWithoutPush() {
        val core = FakeCore().apply { beacon = installed() }
        connectTo(core, Platform(token = null))

        click(R.string.push_enable_button)

        shows(str(R.string.push_unavailable, "no Google Play services"))
        assertTrue(core.pushPaired.isEmpty())
    }

    @Test
    fun saysWhenTheBuildHasNoRelay() {
        connectTo(FakeCore().apply { beacon = installed() }, Platform(relayUrl = null))
        shows(str(R.string.push_no_relay))
    }

    @Test
    fun sendsAnotherTestAndTurnsPushOff() {
        val core = FakeCore().apply { beacon = installed() }
        connectTo(core)
        click(R.string.push_enable_button)
        val before = core.testPushes

        click(R.string.push_test_button)
        assertEquals(before + 1, core.testPushes)

        core.pushDisableHostCleared = false
        click(R.string.push_disable_button)
        assertTrue(core.pushPaired.isEmpty())
        shows(str(R.string.push_off_host_unreachable))
        shows(str(R.string.push_enable_button))
    }

    @Test
    fun removingTheBeaconSendsTheQueuedRevocations() {
        val core = FakeCore().apply { beacon = installed() }
        connectTo(core)
        click(R.string.push_enable_button)
        val flushesBefore = core.revocationFlushes

        click(R.string.beacon_remove_button)
        compose.onNodeWithText(str(R.string.beacon_remove_title)).assertExists()
        compose.onAllNodesWithTextLast(str(R.string.beacon_remove_button))

        assertTrue(core.pushPaired.isEmpty())
        assertEquals(flushesBefore + 1, core.revocationFlushes)
        shows(str(R.string.push_needs_beacon))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextLast(text: String) {
        onAllNodes(androidx.compose.ui.test.hasText(text)).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForIdle()
    }

    private companion object {
        const val RELAY = "https://relay.example"
    }
}
