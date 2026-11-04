package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.ui.NotificationTapApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = NotificationTapApplication::class, qualifiers = "h1200dp", sdk = [35])
class NotificationLockRecallTest {
    @get:Rule val compose = createEmptyComposeRule()

    // Regression D95: a cold or warm tap, followed by lock-screen rotation/Back,
    // must preserve a Call until authentication actually consumes the original intent.
    @Test fun lockedTapsSurviveRecreationAndRetireOnlyWhenTheirTargetIsConsumed() {
        val app = ApplicationProvider.getApplicationContext<NotificationTapApplication>()
        val core = app.core
        val host = core.addHost("box", Endpoint.Ssh("box", 22u, "me"))
        core.ensureKey()
        core.terminals = listOf(TerminalInfo("t1", "Agent", "", emptyList(), null, "live", AgentStatus.Working))
        app.getSharedPreferences("app-guidance", 0).edit().putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        app.appLock.enableAfterAuthentication()
        app.appLock.backgrounded()
        app.appLock.foregrounded()
        val manager = app.getSystemService(NotificationManager::class.java)
        fun call(id: String): Notification {
            PushService.show(app, PushNotice(host.id, host.name,
                PushContent.Call(id, "t1", true, "Review", "Ready", CallUrgency.CHECK, listOf("Yes"), 1uL))) { true }
            return manager.activeNotifications.single { it.tag == "call:$id" }.notification
        }
        val first = call("c1")
        val controller = Robolectric.buildActivity(MainActivity::class.java, shadowOf(first.contentIntent).savedIntent).setup()
        try {
            compose.waitForIdle()
            assertTrue(NotificationGroups.canRestore(app, host.id, "call:c1", first))
            assertEquals(0, first.flags and Notification.FLAG_AUTO_CANCEL)
            controller.recreate()
            compose.waitForIdle()
            assertTrue(manager.activeNotifications.any { it.tag == "call:c1" })
            app.appLock.authenticationSucceeded()
            controller.pause().resume()
            compose.waitForIdle()
            assertFalse(manager.activeNotifications.any { it.tag == "call:c1" })
            assertFalse(NotificationGroups.canRestore(app, host.id, "call:c1", first))

            app.appLock.backgrounded()
            app.appLock.foregrounded()
            compose.waitForIdle()
            val second = call("c2")
            controller.newIntent(shadowOf(second.contentIntent).savedIntent)
            compose.waitForIdle()
            assertTrue(NotificationGroups.canRestore(app, host.id, "call:c2", second))
            app.appLock.authenticationSucceeded()
            controller.pause().resume()
            compose.waitForIdle()
            assertFalse(manager.activeNotifications.any { it.tag == "call:c2" })
        } finally { controller.pause().stop().destroy() }
    }
}
