package io.github.shiroel.aries

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import io.github.shiroel.aries.ui.FakeCore
import io.github.shiroel.aries.weardata.WatchCommand
import io.github.shiroel.aries.weardata.WatchResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [33])
class WatchAnswerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun terminal(status: AgentStatus) = TerminalInfo("remote/t7", "Agent", "", listOf("agent"), "/repo", "live", status)

    @Test fun aLateWatchAnswerNeverAnswersTheReplacementRequest() = runBlocking {
        val core = FakeCore().apply { ensureKey() }
        val host = core.addHost("Host", Endpoint.Ssh("host", 22u, "me"))
        val previous = terminal(AgentStatus.Blocked(BlockedReason.QUESTION, "Old question"))
        val row = WatchPhone.summary(core.hosts(), listOf(LastKnownState(host.id, listOf(previous), 1u))).inbox.single()
        core.terminalsOf[host.id] = listOf(terminal(AgentStatus.Blocked(BlockedReason.QUESTION, "Replacement question")))
        val result = WatchActions.execute(context, core, WatchCommand(row, choice = 0))
        assertEquals(WatchResult.CHANGED, result)
        assertTrue(core.backgroundAnswers.isEmpty())
    }

    @Test fun biometricHostsRejectBothAnswerAndReplyRpcBeforeOpeningAnyConnection() = runBlocking {
        val core = (context as FakeCoreApp).core
        core.ensureKey()
        val host = core.addHost("Host", Endpoint.Ssh("host", 22u, "me"))
        val row = WatchPhone.summary(core.hosts(), listOf(LastKnownState(host.id,
            listOf(terminal(AgentStatus.Done)), 1u))).inbox.single()
        core.setKeyProtection(KeyProtection.BIOMETRIC)
        val controller = org.robolectric.Robolectric.buildService(WatchPhoneService::class.java).create()
        try {
            for ((path, command) in listOf(
                WatchCommand.ANSWER_PATH to WatchCommand(row, choice = 0),
                WatchCommand.REPLY_PATH to WatchCommand(row, text = "Continue"),
            )) {
                val response = controller.get().onRequest("watch", path, command.toBytes())!!.await()
                assertEquals(WatchResult.LOCKED, WatchResult.fromBytes(response))
            }
            assertTrue(core.backgroundAnswers.isEmpty())
            assertTrue(core.backgroundMessages.isEmpty())
            assertTrue(core.snapshotsRequested.isEmpty())
            assertTrue(core.chatsOpened.isEmpty())
        } finally { controller.destroy() }
    }

    private fun question(id: String, permission: Boolean = false, title: String = "Color?") = ChatEntry(id, null,
        ChatEntryBody.Question(if (permission) "permission" else "AskUserQuestion", title,
            listOf(ChatQuestionPart(title, null, false, if (permission) listOf(
                ChatQuestionOption("Allow", null), ChatQuestionOption("Allow always", null), ChatQuestionOption("Deny", null)
            ) else listOf(ChatQuestionOption("Red", null), ChatQuestionOption("Blue", null)))), ChatToolStatus.RUNNING, null))

    @Test fun replacementQuestionWithTheSameStatusMessageIsStillRejectedByItsLiveIdentity() = runBlocking {
        val core = FakeCore().apply { ensureKey() }
        val host = core.addHost("Host", Endpoint.Ssh("host", 22u, "me"))
        core.terminalsOf[host.id] = listOf(terminal(AgentStatus.Blocked(BlockedReason.QUESTION, "Choose")))
        val row = WatchPhone.summary(core.hosts(), listOf(core.snapshot(host.id))).inbox.single().copy(requestId = "old")
        core.pushChat(ChatSnapshot(listOf(question("new")), ChatStatus.LIVE, null))
        assertEquals(WatchResult.CHANGED, WatchActions.execute(context, core, WatchCommand(row, choice = 0)))
        assertTrue(core.backgroundAnswers.isEmpty())
        assertTrue(core.chatStopped)
    }

    @Test fun permissionDenyKeepsItsHostIndexAndNeverExposesAllowAlwaysAsAnAnswer() = runBlocking {
        val core = FakeCore().apply { ensureKey() }
        val host = core.addHost("Host", Endpoint.Ssh("host", 22u, "me"))
        core.terminalsOf[host.id] = listOf(terminal(AgentStatus.Blocked(BlockedReason.PERMISSION, "Edit")))
        val row = WatchPhone.summary(core.hosts(), listOf(core.snapshot(host.id))).inbox.single().copy(requestId = "permission:edit")
        val entry = question("permission:edit", true, "Would you like to make the following edits?")
        core.pushChat(ChatSnapshot(listOf(entry), ChatStatus.LIVE, null))
        assertEquals(WatchResult.PHONE, WatchActions.execute(context, core, WatchCommand(row, choice = 1)))
        assertTrue(core.backgroundAnswers.isEmpty())
        core.pushChat(ChatSnapshot(listOf(entry), ChatStatus.LIVE, null))
        assertEquals(WatchResult.SENT, WatchActions.execute(context, core, WatchCommand(row, choice = 2)))
        assertEquals(listOf(Triple("remote/t7", "permission:edit", listOf(listOf(2u)))), core.backgroundAnswers)
    }

    @Test fun commandApprovalCannotBypassThePhoneHandoffWithAForgedWatchFlag() = runBlocking {
        val core = FakeCore().apply { ensureKey() }
        val host = core.addHost("Host", Endpoint.Ssh("host", 22u, "me"))
        core.terminalsOf[host.id] = listOf(terminal(AgentStatus.Blocked(BlockedReason.PERMISSION, "Run command")))
        val row = WatchPhone.summary(core.hosts(), listOf(core.snapshot(host.id))).inbox.single()
            .copy(requestId = "permission:command", canAnswerOnWatch = true)
        core.pushChat(ChatSnapshot(listOf(question("permission:command", true,
            "Would you like to run the following command?")), ChatStatus.LIVE, null))
        assertEquals(WatchResult.PHONE, WatchActions.execute(context, core, WatchCommand(row, choice = 0)))
        assertTrue(core.backgroundAnswers.isEmpty())
    }

    @Test fun doneReplyUsesTheBackgroundSendPathButNeverQueuesAReplyAfterTheAgentStartsWorking() = runBlocking {
        val core = FakeCore().apply { ensureKey() }
        val host = core.addHost("Host", Endpoint.Ssh("host", 22u, "me"))
        core.terminalsOf[host.id] = listOf(terminal(AgentStatus.Done))
        val row = WatchPhone.summary(core.hosts(), listOf(core.snapshot(host.id))).inbox.single()
        assertEquals(WatchResult.SENT, WatchActions.execute(context, core, WatchCommand(row, text = "Run the tests")))
        core.terminalsOf[host.id] = listOf(terminal(AgentStatus.Working))
        assertEquals(WatchResult.CHANGED, WatchActions.execute(context, core, WatchCommand(row, text = "Stale reply")))
        assertEquals(listOf(Triple(host.id, "remote/t7", "Run the tests")), core.backgroundMessages)
    }

}
