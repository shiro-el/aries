package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.BeaconFetcher
import io.github.shiroel.aries.core.BeaconSkillOutcome
import io.github.shiroel.aries.core.BeaconSkillRoot
import io.github.shiroel.aries.core.BeaconState
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Linger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: the Beacon section of a connected Host against a fake core API. */
@RunWith(RobolectricTestRunner::class)
class BeaconSectionTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val noDownload = object : BeaconFetcher {
        override fun fetch(url: String): ByteArray = error("the fake core does not download")
    }

    private fun connectTo(core: FakeCore) {
        core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, noDownload) }
            MaterialTheme { AriesApp(model) }
        }
        compose.openHostActions("h1")
        compose.waitForIdle()
    }

    private fun click(res: Int) {
        compose.onNodeWithText(str(res)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun installsTheBeaconAndShowsItsVersionAndCore() {
        val core = FakeCore()
        connectTo(core)
        compose.onNodeWithText(str(R.string.beacon_not_installed)).assertExists()

        click(R.string.beacon_install_button)

        compose.onNodeWithText(str(R.string.beacon_version, "0.1.0")).assertExists()
        compose.onNodeWithText(str(R.string.beacon_attached, "0.1.17")).assertExists()
    }

    @Test
    fun showsBothForeignSkillPathsOnConnectAndAfterReinstall() {
        val core = FakeCore().apply {
            beaconSkills = listOf("/home/me/.claude/skills/aries-call-human", "/home/me/.agents/skills/aries-call-human").map {
                BeaconSkillRoot(it, BeaconSkillOutcome.KEPT_FOREIGN)
            }
            beacon = installed("0.1.0")
        }
        connectTo(core)
        fun assertConflicts() {
            core.beaconSkills!!.forEach { skill ->
                compose.onNodeWithText(skill.path).assertExists()
            }
            compose.onAllNodesWithText(
                str(R.string.beacon_skill_foreign),
            ).assertCountEquals(2)
        }
        assertConflicts()
        click(R.string.beacon_reinstall_button)
        assertConflicts()
    }


    @Test
    fun onlyTheNewSkillNameDeterminesReadinessAndConflicts() {
        val core = FakeCore().apply {
            beaconSkills = listOf(
                BeaconSkillRoot("/home/me/.claude/skills/aries-call-human", BeaconSkillOutcome.UNCHANGED),
                BeaconSkillRoot("/home/me/.agents/skills/aries-call-human", BeaconSkillOutcome.INSTALLED),
                BeaconSkillRoot("/home/me/.claude/skills/call-human", BeaconSkillOutcome.KEPT_FOREIGN),
                BeaconSkillRoot("/home/me/.agents/skills/call-human", BeaconSkillOutcome.KEPT_SYMLINK),
            )
            beacon = installed("0.1.0")
        }
        connectTo(core)
        assertEquals("aries-call-human skill", str(R.string.beacon_skill_title))
        compose.onAllNodesWithText(str(R.string.beacon_skill_ready)).assertCountEquals(2)
        compose.onAllNodesWithText(str(R.string.beacon_skill_foreign)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.beacon_skill_symlink)).assertCountEquals(0)
        compose.onAllNodesWithText("/home/me/.claude/skills/call-human").assertCountEquals(0)
    }

    @Test
    fun oldNameOnlyReportsDoNotClaimNewSkillReadinessOrConflict() {
        val core = FakeCore().apply {
            beaconSkills = listOf(
                BeaconSkillRoot("/home/me/.claude/skills/call-human", BeaconSkillOutcome.KEPT_FOREIGN),
                BeaconSkillRoot("/home/me/.agents/skills/call-human", BeaconSkillOutcome.UNCHANGED),
            )
            beacon = installed("0.1.0")
        }
        connectTo(core)
        compose.onNodeWithText(str(R.string.beacon_skill_unknown)).assertExists()
        compose.onAllNodesWithText(str(R.string.beacon_skill_foreign)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.beacon_skill_ready)).assertCountEquals(0)
    }

    @Test
    fun showsModifiedAndSymlinkPreservationReasons() {
        val core = FakeCore().apply {
            beaconSkills = listOf(
                BeaconSkillRoot("/home/me/.claude/skills/aries-call-human", BeaconSkillOutcome.KEPT_MODIFIED),
                BeaconSkillRoot("/home/me/.agents/skills/aries-call-human", BeaconSkillOutcome.KEPT_SYMLINK),
            )
            beacon = installed("0.1.0")
        }
        connectTo(core)
        compose.onNodeWithText(str(R.string.beacon_skill_modified)).assertExists()
        compose.onNodeWithText(str(R.string.beacon_skill_symlink)).assertExists()
    }

    @Test
    fun anOlderBeaconDoesNotImplyThatTheCallSkillIsReady() {
        val core = FakeCore().apply {
            beaconSkills = null
            beacon = installed("0.1.0")
        }
        connectTo(core)
        compose.onNodeWithText(str(R.string.beacon_skill_unknown)).assertExists()
    }

    @Test
    fun explainsHowToKeepTheBeaconRunningWhenLingerFails() {
        val core = FakeCore().apply { beaconLinger = Linger.Failed("Access denied") }
        connectTo(core)

        click(R.string.beacon_install_button)

        compose.onNodeWithText(str(R.string.beacon_linger_title)).assertExists()
        compose.onNodeWithText(str(R.string.beacon_linger_body, "Access denied", "me")).assertExists()
    }

    @Test
    fun reportsABinaryThatFailedTheChecksum() {
        val core = FakeCore().apply { beaconFailWith = CoreException.BeaconChecksum("aa", "bb") }
        connectTo(core)

        click(R.string.beacon_install_button)

        compose.onNodeWithText(str(R.string.beacon_checksum_body, "aa", "bb")).assertExists()
        assertEquals(BeaconState.NotInstalled, core.beacon)
    }

    @Test
    fun flagsABeaconOutsideTheSupportedRange() {
        val core = FakeCore().apply {
            beacon = installed("9.0.0", supported = false)
            autoUpdate = false
        }
        connectTo(core)

        compose.onNodeWithText(str(R.string.beacon_out_of_range, "9.0.0", ">=0.1.0, <0.2.0")).assertExists()
    }

    @Test
    fun updatesAnUnsupportedBeaconWhenConnecting() {
        val core = FakeCore().apply { beacon = installed("0.0.9", supported = false) }
        connectTo(core)

        assertEquals(1, core.installs)
        compose.onNodeWithText(str(R.string.beacon_updated, "0.0.9", "0.1.0")).assertExists()
        compose.onNodeWithText(str(R.string.beacon_version, "0.1.0")).assertExists()
    }

    @Test
    fun withAutoUpdateOffOnlySaysAnUpdateIsNeeded() {
        val core = FakeCore().apply {
            beacon = installed("0.0.9", supported = false)
            autoUpdate = false
        }
        connectTo(core)

        assertEquals(0, core.installs)
        compose.onNodeWithText(str(R.string.beacon_update_needed, "0.0.9", "0.1.0")).assertExists()
        compose.onNodeWithText(str(R.string.beacon_version, "0.0.9")).assertExists()

        click(R.string.beacon_update_button)

        assertEquals(1, core.installs)
        compose.onNodeWithText(str(R.string.beacon_version, "0.1.0")).assertExists()
    }

    @Test
    fun turnsAutomaticUpdatesOff() {
        val core = FakeCore().apply { beacon = installed("0.1.0") }
        connectTo(core)
        assertEquals(true, core.autoUpdate)

        click(R.string.beacon_auto_update)

        assertEquals(false, core.autoUpdate)
    }

    @Test
    fun removesTheBeaconAfterConfirmation() {
        val core = FakeCore().apply {
            beacon = installed("0.1.0")
        }
        connectTo(core)

        click(R.string.beacon_remove_button)
        assertEquals(true, core.beacon is BeaconState.Installed)

        compose.onAllNodesWithText(str(R.string.beacon_remove_button)).onLast().performClick() // the dialog's
        compose.waitForIdle()

        assertEquals(BeaconState.NotInstalled, core.beacon)
        compose.onNodeWithText(str(R.string.beacon_not_installed)).assertExists()
    }
}
