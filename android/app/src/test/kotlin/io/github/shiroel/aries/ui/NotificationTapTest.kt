package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.app.Notification
import android.app.NotificationManager
import io.github.shiroel.aries.CallNotice
import io.github.shiroel.aries.CallNotifications
import io.github.shiroel.aries.CallView
import io.github.shiroel.aries.PushService
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.core.PushQuestion
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keep production Activity visibility tracking while faking device-only key storage. */
class NotificationTapApplication : io.github.shiroel.aries.AriesApplication(), io.github.shiroel.aries.HasNotificationVault {
    override val core = FakeCore()
    override val notificationVault = object : io.github.shiroel.aries.core.KeyVault {
        override fun seal(plaintext: ByteArray, protection: io.github.shiroel.aries.core.KeyProtection) =
            plaintext.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
        override fun open(sealed: ByteArray, protection: io.github.shiroel.aries.core.KeyProtection) = seal(sealed, protection)
    }
}

/** Seam 3: tapping an Agent notification opens that Agent's Terminal, against a fake core. */
@RunWith(RobolectricTestRunner::class)
@Config(application = NotificationTapApplication::class)
class NotificationTapTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun agent(address: String, name: String, status: AgentStatus?, state: String = "live") = TerminalInfo(
        address, name, "", listOf(name), "/repo/$name", state, status, false, 0uL,
        false, address.substringBeforeLast('/', "").ifEmpty { null },
    )

    /** Two Hosts that both have a `t1`; home's Agent on its machine `box` is Blocked. */
    private fun twoHosts() = FakeCore().apply {
        addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        addHost("server", Endpoint.Ssh("10.0.0.3", 22u, "me"))
        terminalsOf["h1"] = listOf(
            agent("t1", "claude", AgentStatus.Working),
            agent("box/t1", "codex", AgentStatus.Blocked(BlockedReason.PERMISSION, "Allow Bash?")),
        )
        terminalsOf["h2"] = listOf(agent("t1", "pi", AgentStatus.Error(null)))
    }

    private lateinit var model: HostsModel

    private fun show(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.waitForIdle()
    }

    private fun tap(hostId: String, address: String) {
        compose.runOnIdle { model.openPushedAgent(hostId, address) }
        compose.waitForIdle()
    }

    @Test
    fun foregroundRequestsAreQuietAndOnlyTheViewedHostAndAgentAreSuppressed() {
        show(twoHosts())
        val manager = context.getSystemService(NotificationManager::class.java)
        val question = PushQuestion("q1", "Which color?", listOf("Red", "Blue"))
        fun ask(host: String = "h1") = PushService.show(context, PushNotice(host, "box",
            PushContent.Status("t1", "claude", null, true,
                AgentStatus.Blocked(BlockedReason.QUESTION, question.text), 10uL, 1uL, question, null))) { true }
        fun call() = PushService.show(context, PushNotice("h1", "box",
            PushContent.Call("c1", "t1", true, "Review", "Ready", CallUrgency.URGENT, listOf("Continue", "Stop"), 10uL))) { true }
        fun quietCard(host: String, tag: String): Notification {
            val notification = manager.activeNotifications.single { it.id == host.hashCode() && it.tag == tag }.notification
            val channel = manager.getNotificationChannel(notification.channelId)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertEquals(null, channel.sound)
            assertEquals(false, channel.shouldVibrate())
            assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
            assertEquals(notification.actions.map { it.actionIntent }, Notification.WearableExtender(notification).actions.map { it.actionIntent })
            assertEquals(2, notification.actions.size)
            return notification
        }

        compose.runOnIdle { ask(); call() }
        quietCard("h1", "t1")
        quietCard("h1", "call:c1")

        tap("h1", "t1") // Terminal view of the requesting Agent.
        compose.runOnIdle { ask(); call(); ask("h2") }
        assertTrue(manager.activeNotifications.none { it.id == "h1".hashCode() })
        quietCard("h2", "t1") // Same Terminal address on a different Host must still notify.

        compose.runOnIdle { model.openQuickOpen() }
        compose.waitForIdle()
        compose.runOnIdle { ask(); call() }
        quietCard("h1", "t1") // A covered Agent is not the screen the user is viewing.
        quietCard("h1", "call:c1")
        compose.runOnIdle { model.closeQuickOpen() }
        compose.waitForIdle()

        compose.runOnIdle { model.switchView(AgentView.CHAT) }
        compose.waitForIdle()
        compose.runOnIdle {
            ask()
            // Local Call updates share the same gate as the FCM entry point.
            CallNotifications.post(context,
                CallNotice("h1", "box", PushContent.Call("c1", "t1", true,
                    "Review", "Ready", CallUrgency.URGENT, listOf("Continue", "Stop"), 10uL)),
                CallView.Choices)
        }
        assertTrue(manager.activeNotifications.none { it.id == "h1".hashCode() })

        compose.runOnIdle { model.closeChat() }
        compose.waitForIdle()
        compose.runOnIdle { ask(); call() }
        quietCard("h1", "t1")
        quietCard("h1", "call:c1")
    }

    @Test
    fun opensTheExactHostAndMachineTerminalAndBackShowsTheList() {
        val core = twoHosts()
        show(core)
        tap("h1", "box/t1")

        val view = core.connectionsOf.getValue("h1").views.single()
        assertEquals("box/t1", view.address)
        assertTrue("attaches as an observer", view.options.observe)
        assertTrue(core.connectionsOf["h2"]?.views.orEmpty().isEmpty())
        assertTrue(model.screen is Screen.Terminal)

        // Back leaves the Terminal for the Agents list.
        compose.onNodeWithContentDescription(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(view.detached)
        assertTrue(model.screen is Screen.Agents)
        compose.onNodeWithText("codex").assertExists()
        compose.onNodeWithText("claude").assertExists()
    }

    @Test
    fun aQuestionAnswerActionOpensTheExactChatWithoutChangingTheDefaultView() {
        val core = twoHosts().apply { savedView = AgentView.TERMINAL }
        show(core)
        compose.runOnIdle { model.openPushedAgent("h1", "box/t1", openChat = true) }
        compose.waitForIdle()

        assertTrue(model.screen is Screen.Chat)
        assertEquals(listOf("h1" to "box/t1"), core.chatsOpened)
        assertTrue(core.connectionsOf.getValue("h1").views.isEmpty())
        assertEquals(AgentView.TERMINAL, core.savedView)
    }

    @Test
    fun aDoneNotificationOpensDirectlyAndMarksTheTurnSeen() {
        val core = twoHosts().apply {
            terminalsOf["h2"] = listOf(agent("t1", "pi", AgentStatus.Done).copy(turn = 4uL))
        }
        show(core)
        tap("h2", "t1")

        assertTrue(model.screen is Screen.Terminal)
        assertEquals("t1", core.connectionsOf.getValue("h2").views.single().address)
        assertEquals(listOf("h2 t1 seen=4"), core.labelWrites)
    }

    @Test
    fun aTapWhileAnotherTerminalIsOpenSwitchesToTheTappedOne() {
        val core = twoHosts()
        show(core)
        tap("h1", "t1")
        val first = core.connectionsOf.getValue("h1").views.single()

        // Warm start: the app is showing home's t1 when server's t1 notifies.
        tap("h2", "t1")
        assertTrue(first.detached)
        val view = core.connectionsOf.getValue("h2").views.single()
        assertEquals("t1", view.address)
        assertTrue(model.screen is Screen.Terminal)
    }

    @Test
    fun aTerminalThatIsGoneSaysWhyAndShowsTheAgentsList() {
        val core = twoHosts().apply {
            terminalsOf["h1"] = listOf(agent("t1", "claude", AgentStatus.Working), agent("box/t1", "codex", null, state = "exited"))
        }
        show(core)
        tap("h1", "box/t1")

        assertTrue(core.connectionsOf.getValue("h1").views.isEmpty())
        compose.onNodeWithText(str(R.string.push_terminal_gone_title)).assertExists()
        compose.onNodeWithText(str(R.string.push_terminal_gone_body, "box/t1", "home")).assertExists()
        compose.onNodeWithText(str(R.string.ok_button)).performClick()
        compose.waitForIdle()
        assertTrue(model.screen is Screen.Agents)
        compose.onNodeWithText("claude").assertExists()
    }

    @Test
    fun aNotificationFromARemovedHostSaysWhyAndShowsTheAgentsList() {
        val core = twoHosts()
        show(core)
        tap("gone", "t1")

        compose.onNodeWithText(str(R.string.push_terminal_gone_title)).assertExists()
        compose.onNodeWithText(str(R.string.push_host_gone_body, "t1")).assertExists()
        assertTrue(model.screen is Screen.Agents)
        assertTrue(core.connectionsOf.values.all { it.views.isEmpty() })
    }
}
