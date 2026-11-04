package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.BeaconUpdate
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.BeaconCheck
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class SettingsConsolidationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = context.getString(id)
    private fun show(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
    }

    @Test fun hostsHaveOnlyHostsAndAddWhileSettingsOwnsKeys() {
        show(FakeCore().apply { ensureKey() })
        compose.onNodeWithTag("hosts-key-band").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.keys_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.key_create_button)).assertDoesNotExist()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-keys"))
        compose.onNodeWithTag("settings-keys").performClick()
        compose.onNodeWithText(FakeCore.PUBLIC_KEY).assertExists()
        compose.onNodeWithText(str(R.string.key_biometric_label)).assertExists()
        compose.onNodeWithText(str(R.string.keys_title)).performScrollTo().assertExists()
    }

    @Test fun hostMenuOpensItsSettingsWithoutAConnection() {
        val core = FakeCore().apply {
            addHost("box", Endpoint.Ssh("box.example", 22u, "me"))
            failWith = CoreException.AuthRejected()
        }
        show(core)
        compose.onNodeWithTag("host-menu:h1").performClick()
        compose.onNodeWithTag("host-actions:h1").assertExists()
        compose.onNodeWithText(str(R.string.beacon_install_button)).performScrollTo().performClick()
        assertEquals(1, core.installs)
        compose.onNodeWithText(str(R.string.push_title)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.host_jumps_button)).performScrollTo().performClick()
        compose.onNodeWithTag("console-back").performClick()
        compose.onNodeWithTag("host-actions:h1").assertExists()
    }

    @Test fun browseOnlyOffersALinkToTheHostSettings() {
        show(FakeCore().apply { addHost("box", Endpoint.Ssh("box.example", 22u, "me")) })
        compose.onNodeWithText("box").performClick()
        compose.onNodeWithText(str(R.string.beacon_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.push_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.beacon_install_button)).assertDoesNotExist()
        compose.onNodeWithTag("browse-host-settings:h1").performScrollTo().performClick()
        compose.onNodeWithTag("host-actions:h1").assertExists()
        compose.onNodeWithText(str(R.string.beacon_install_button)).assertExists()
    }

    @Test fun missingKeyAtAddCreatesInlineAndContinuesToHostDetails() {
        show(FakeCore())
        compose.onNodeWithText(str(R.string.key_missing)).assertDoesNotExist()
        compose.openHostAddForm()
        compose.onNodeWithText(str(R.string.key_create_button)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.host_add_address)).assertExists()
        compose.onNodeWithTag("settings-keys").assertDoesNotExist()
    }

    @Test fun missingKeyAtConnectCreatesInlineAndOffersReconnect() {
        val core = FakeCore().apply {
            addHost("box", Endpoint.Ssh("box.example", 22u, "me"))
            failWith = CoreException.NoKey()
        }
        show(core)
        compose.onNodeWithText("box").performClick()
        compose.onNodeWithText(str(R.string.key_create_button)).performClick()
        compose.onNodeWithTag("missing-key-connect").assertExists()
        core.failWith = null
        compose.onNodeWithTag("missing-key-connect").performClick()
        compose.onNodeWithTag("browse-host-settings:h1").assertExists()
    }

    @Test fun aLateBeaconResultCannotReplaceAnotherHostsSettings() {
        val core = FakeCore().apply {
            addHost("slow", Endpoint.Ssh("slow.example", 22u, "me"))
            addHost("fast", Endpoint.Ssh("fast.example", 22u, "me"))
        }
        val slow = CompletableDeferred<BeaconCheck>()
        val api = object : AriesCoreInterface by core {
            override suspend fun checkBeacon(hostId: String, fetcher: BeaconFetcher): BeaconCheck =
                if (hostId == "h1") slow.await() else BeaconCheck(
                    core.installed("0.1.0"), BeaconUpdate.NotNeeded,
                )
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(api, scope) }
            AriesApp(model)
        }
        compose.openHostActions("h1")
        compose.onNodeWithTag("console-back").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-host:h2"))
        compose.onNodeWithTag("settings-host:h2").performClick()
        compose.onNodeWithTag("beacon-advanced").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.beacon_version, "0.1.0")).performScrollTo().assertExists()
        compose.runOnIdle {
            slow.complete(BeaconCheck(
                BeaconState.NotInstalled, BeaconUpdate.NotNeeded,
            ))
        }
        compose.onNodeWithText(context.getString(R.string.beacon_version, "0.1.0")).assertExists()
        compose.onNodeWithText(str(R.string.beacon_install_button)).assertDoesNotExist()
    }

    @Test fun settingsSavesTheDefaultAgentView() {
        val core = FakeCore()
        show(core)
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(str(R.string.agent_view_chat)))
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
        compose.runOnIdle { assertEquals(AgentView.CHAT, core.agentView()) }
        compose.onNodeWithTag("console-back").performClick()
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(str(R.string.agent_view_chat)))
        compose.onNodeWithText(str(R.string.agent_view_chat)).assertIsSelected()
    }
}
