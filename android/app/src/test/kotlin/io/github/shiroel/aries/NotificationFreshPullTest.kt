package io.github.shiroel.aries

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [35])
class NotificationFreshPullTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core get() = (context as FakeCoreApp).core
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val blocked = AgentStatus.Blocked(BlockedReason.QUESTION, null)
    @Before fun setup() { WorkManagerTestInitHelper.initializeTestWorkManager(context); core.ensureKey() }

    // D75: legacy pushes can share a second; q1 must not return after q2 replaced it.
    @Test fun aReplacedDismissedQuestionCannotReturnInTheSameSecond() {
        fun ask(id: String, atMs: ULong? = null) = PushService.show(context, PushNotice("host", "Host",
            PushContent.Status("t1", "Agent", null, true, blocked, 10uL, 7uL,
                PushQuestion(id, id, listOf("Yes")), null, atMs = atMs))) { true }
        ask("q1")
        manager.activeNotifications.single { it.tag == "t1" }.notification.deleteIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        ask("q2")
        ask("q1")
        assertEquals("q2", manager.activeNotifications.single { it.tag == "t1" }.notification.extras.getString(NotificationGroups.QUESTION))
        ask("q3", 10_900uL)
        ask("previously-unseen-old-question", 10_100uL)
        assertEquals("q3", manager.activeNotifications.single { it.tag == "t1" }.notification.extras.getString(NotificationGroups.QUESTION))
    }

    // D76: a successful Agent pull does not refresh Chat cache; only live request identity proves q1 ended.
    @Test fun backgroundPullRetiresSamePhaseQuestionUsingLiveRequestInsteadOfOldChat() = runBlocking {
        val host = core.addHost("Host", Endpoint.Ssh("host", 22u, "me"))
        core.terminals = listOf(TerminalInfo("t1", "Agent", "", emptyList(), null, "live", blocked, turn = 7uL))
        fun question(id: String) = ChatEntry(id, null, ChatEntryBody.Question("request_user_input", "",
            emptyList(), ChatToolStatus.RUNNING, null))
        core.cachedChats[host.id to "t1"] = ChatSnapshot(listOf(question("q1")), ChatStatus.LIVE, null)
        PushService.show(context, PushNotice(host.id, host.name, PushContent.Status("t1", "Agent", null,
            true, blocked, 1uL, 7uL, PushQuestion("q1", "First?", listOf("Yes")), null))) { true }
        // readChat returns q2; the local encrypted cache deliberately remains q1.
        core.pushChat(ChatSnapshot(listOf(question("q2")), ChatStatus.LIVE, null))
        TestListenableWorkerBuilder<LastKnownStateWorker>(context).build().doWork()
        assertFalse(manager.activeNotifications.any { it.tag == "t1" })
        assertEquals("q1", core.cachedChats.getValue(host.id to "t1").entries.single().id)
    }

    // D79: a Host clock either ahead or behind the phone cannot prevent fresh pull recall;
    // an already stored snapshot from before arrival still cannot erase a new push.
    @Test fun recallUsesPhoneObservationOrderRegardlessOfHostClockSkew() = runBlocking {
        for ((index, skew) in listOf(-120_000L, 120_000L).withIndex()) {
            val terminal = "t$index"
            val now = System.currentTimeMillis()
            val at = ((now + skew) / 1000).toULong()
            PushService.show(context, PushNotice("host", "Host", PushContent.Status(terminal, "Agent", null,
                true, AgentStatus.Done, at, 7uL, null, null)))
            val working = TerminalInfo(terminal, "Agent", "", emptyList(), null, "live", AgentStatus.Working, turn = 7uL)
            NotificationGroups.reconcile(context, core, listOf(LastKnownState("host", listOf(working), (now - 1_000).toULong())))
            assertTrue(manager.activeNotifications.any { it.tag == terminal })
            NotificationGroups.reconcile(context, core, listOf(LastKnownState("host", listOf(working), (System.currentTimeMillis() + 1).toULong())))
            assertFalse(manager.activeNotifications.any { it.tag == terminal })
            val progressTerminal = "p$index"
            PushService.show(context, PushNotice("host", "Host", PushContent.Progress(progressTerminal, "Agent", 1uL, 0u, at, false)))
            NotificationGroups.reconcile(context, core, listOf(LastKnownState("host", emptyList(), (System.currentTimeMillis() + 1).toULong())))
            assertFalse(manager.activeNotifications.any { it.tag == progressTerminal })
            assertTrue(WatchProgressPhone.all(context).single { it.terminal == progressTerminal }.cancelled)
        }
    }
}
