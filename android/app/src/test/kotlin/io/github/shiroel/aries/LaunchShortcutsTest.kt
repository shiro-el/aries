package io.github.shiroel.aries

import android.content.Context
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import io.github.shiroel.aries.ui.*
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [33])
class LaunchShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core get() = (context as FakeCoreApp).core
    private val manager get() = context.getSystemService(ShortcutManager::class.java)
    private fun saved(id: String) = SavedLaunch(id, "Codex $id", LaunchRequest("--model fast", "/repo", null, null, AgentPreset.CODEX))

    @Test fun publishesFourStableHostScopedShortcutsAndUpdatesAndDisablesPinnedCopies() {
        val first = core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val other = core.addHost("work", Endpoint.Ssh("work", 22u, "me"))
        core.savedLaunchesOf[first.id] = mutableListOf(saved("1"), saved("2"))
        core.savedLaunchesOf[other.id] = mutableListOf(saved("1"), saved("2"), saved("3"))
        LaunchShortcuts.sync(context, core)
        val before = manager.dynamicShortcuts.sortedBy { it.rank }
        assertEquals(4, before.size)
        assertEquals(4, before.map { it.id }.distinct().size)
        assertEquals(SavedLaunchTarget(other.id, "1"), SavedLaunchTarget.from(before[2].intent))
        assertEquals("Codex 1", before[2].shortLabel.toString())
        assertTrue(before[2].longLabel.toString().contains("work"))
        assertNull(core.connection)
        shadowOf(manager).setIsRequestPinShortcutSupported(true)
        assertTrue(LaunchShortcuts.pin(context, other.id, saved("1")))
        core.savedLaunchesOf.getValue(other.id)[0] = saved("1").copy(name = "Renamed")
        LaunchShortcuts.sync(context, core)
        // Robolectric keeps pinned and dynamic records in separate collections.
        assertEquals("Renamed", (manager.dynamicShortcuts + manager.pinnedShortcuts).first { it.id == before[2].id }.shortLabel.toString())
        core.savedLaunchesOf.getValue(other.id).removeAt(0)
        LaunchShortcuts.sync(context, core)
        assertFalse(manager.dynamicShortcuts.any { it.id == before[2].id })
        // The shadow enforces disabled pin rejection but does not update isEnabled.
        assertThrows(IllegalArgumentException::class.java) { manager.requestPinShortcut(before[2], null) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun shortcutAuthenticatesOnItsHostThenLaunchesTheLatestSavedRequestExactlyOnce() = runTest {
        val host = core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val other = core.addHost("work", Endpoint.Ssh("work", 22u, "me"))
        val latest = saved("1").copy(request = saved("1").request.copy(command = "--model latest"))
        core.savedLaunchesOf[host.id] = mutableListOf(latest)
        core.savedLaunchesOf[other.id] = mutableListOf(saved("1"))
        core.ensureKey()
        core.setKeyProtection(KeyProtection.BIOMETRIC)
        val model = HostsModel(core, backgroundScope, keyWork = inKeyWork.asContextElement(true))
        val target = SavedLaunchTarget.from(SavedLaunchTarget(host.id, "1").intent(context))!!
        model.openSavedLaunch(target.hostId, target.launchId)
        runCurrent()
        assertEquals(listOf(latest.request), core.connection!!.launched)
        assertEquals(host.id, (model.screen as Screen.Browse).browser.host.id)
        assertTrue(core.offKeyWork.isEmpty())
        val launched = core.connection!!
        launched.push(HostStructure(emptyList(), emptyList(), null))
        runCurrent()
        assertEquals(1, launched.launched.size)
        model.back()
        runCurrent()
        core.failWith = CoreException.KeyLocked()
        model.openSavedLaunch(host.id, "1")
        runCurrent()
        assertTrue(model.screen is Screen.Hosts)
        assertTrue(model.prompt is Prompt.Problem)
        assertEquals(1, launched.launched.size)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun anUnavailableShortcutDoesNotRestoreAnUnrelatedLastLocation() = runTest {
        val host = core.addHost("home", Endpoint.Ssh("home", 22u, "me"))
        val store = LastLocationStore.from(context)
        store.save(LastLocation(hostId = host.id))
        val model = HostsModel(core, backgroundScope, locationStore = store)
        model.openSavedLaunch("removed", "1")
        model.start()
        runCurrent()
        assertTrue(model.screen is Screen.Hosts)
        assertNull(core.connection)
    }
}
