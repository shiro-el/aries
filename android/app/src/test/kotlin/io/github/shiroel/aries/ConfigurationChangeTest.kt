package io.github.shiroel.aries

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import io.github.shiroel.aries.ui.FakeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class ConfigurationChangeApplication : AriesApplication() {
    val fake = FakeCore().apply {
        addHost("rotation-host", Endpoint.Ssh("home.test", 22u, "me"))
        terminals = listOf(TerminalInfo("t1", "rotation-terminal", "", listOf("sh"), "/repo", "live", null))
    }
    var connects = 0
    override val core: AriesCoreInterface = object : AriesCoreInterface by fake {
        override suspend fun connect(hostId: String) = fake.connect(hostId).also { connects++ }
    }
}

/** Seam 3: real MainActivity recreation must preserve its live core session and editable state. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ConfigurationChangeApplication::class, qualifiers = "h1200dp")
class ConfigurationChangeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun prepareApplication(): ConfigurationChangeApplication {
        val app = ApplicationProvider.getApplicationContext<ConfigurationChangeApplication>()
        app.getSharedPreferences("app-guidance", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        return app
    }

    @Test fun recreationKeepsConnectionScrollbackAndUnsentDraftUntilActivityFinishes() {
        val app = prepareApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            compose.onNodeWithText("rotation-host").performClick()
            compose.waitUntil(5_000) { app.fake.connection != null }
            compose.onNodeWithText("rotation-terminal").performClick()
            compose.waitUntil(5_000) { app.fake.connection!!.views.isNotEmpty() }
            val connection = app.fake.connection!!
            val view = connection.views.single()
            view.push(TerminalUpdate.Screen(ScreenFrame(80u, 24u, true, emptyList(), null, null, 42u, 500u)))
            compose.onNodeWithText(app.getString(R.string.terminal_scrolled_back, 42)).assertExists()
            compose.onNodeWithContentDescription(app.getString(R.string.terminal_compose_button)).performClick()
            compose.onNodeWithTag("terminal-composer").performTextReplacement("unsent\n한국어 draft")

            activity.recreate()
            compose.waitForIdle()

            assertEquals("rotation must not reconnect", 1, app.connects)
            assertSame(connection, app.fake.connection)
            assertFalse(connection.disconnected)
            assertFalse(view.detached)
            compose.onNodeWithText(app.getString(R.string.terminal_scrolled_back, 42)).assertExists()
            compose.onNodeWithTag("terminal-composer").assertTextEquals("unsent\n한국어 draft")
            // A live stream still works after recreation; keeping a frozen old screen is insufficient.
            view.push(TerminalUpdate.Screen(ScreenFrame(80u, 24u, true, emptyList(), null, null, 43u, 501u)))
            compose.onNodeWithText(app.getString(R.string.terminal_scrolled_back, 43)).assertExists()
            assertTrue(view.sent.isEmpty())
        } finally {
            activity.pause().stop().destroy()
        }
        compose.waitForIdle()
        assertTrue("finishing releases the Host connection", app.fake.connection!!.disconnected)
        assertTrue("finishing detaches the Terminal", app.fake.connection!!.views.single().detached)
    }

    @Test @Config(sdk = [33])
    fun notificationPermissionResultReachesItsOriginalCallerAfterRecreation() {
        val app = prepareApplication()
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val owner = ViewModelProvider(activity.get())[HostsViewModel::class.java]
            val result = compose.runOnIdle { owner.viewModelScope.async { owner.push.allowNotifications() } }
            val request = shadowOf(activity.get()).nextStartedActivityForResult
                ?: error("notification permission was not requested")
            assertFalse(result.isCompleted)

            activity.recreate()
            compose.waitForIdle()
            assertNull("recreation must not request permission twice", shadowOf(activity.get()).nextStartedActivityForResult)
            compose.runOnIdle {
                activity.get().onRequestPermissionsResult(request.requestCode,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
            }
            compose.waitUntil(5_000) { result.isCompleted }
            assertTrue(kotlinx.coroutines.runBlocking { result.await() })
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
