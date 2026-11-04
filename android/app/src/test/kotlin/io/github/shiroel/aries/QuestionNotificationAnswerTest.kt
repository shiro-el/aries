package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.shiroel.aries.core.AnswerProblem
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.KeyProtection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import io.github.shiroel.aries.core.PushQuestion
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Notification/worker ↔ core seam: the question ID and option indexes must survive. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class)
class QuestionNotificationAnswerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val core get() = (context as FakeCoreApp).core
    private lateinit var hostId: String

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        hostId = core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me")).id
        core.ensureKey()
    }

    private fun push(question: PushQuestion? = PushQuestion("q1", "Which color?", listOf("Red", "Blue", "Green"))) =
        PushNotice(hostId, "box", PushContent.Status("remote/t3", "Agent", null, true,
            AgentStatus.Blocked(BlockedReason.QUESTION, "Which color?"), 1uL, null, question, null))

    private fun shown(): Notification = manager.activeNotifications.single { it.tag == "remote/t3" }.notification
    private fun buttons() = shown().actions.orEmpty().map { it.title.toString() }
    private fun text() = shown().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun show() = PushService.show(context, push()) { core.canAnswerInBackground(it) }
    private fun notice(): QuestionNotice {
        show()
        return QuestionNotice.fromJson(NotificationPayloads.open(context, shadowOf(shown().actions[1].actionIntent).savedIntent
            .getStringExtra(QuestionChoiceReceiver.EXTRA_NOTICE))!!)!!
    }
    private fun runWorker(choice: Int = 1): ListenableWorker.Result = runBlocking {
        val notice = notice()
        TestListenableWorkerBuilder<QuestionReplyWorker>(context)
            .setInputData(QuestionReplyWorker.input(context, notice, choice)).build().doWork()
    }

    @Test
    fun questionTextAndOptionsAreSealedInWorkAndPendingIntents() {
        val notice = notice()
        val input = QuestionReplyWorker.input(context, notice, 0).toByteArray().toString(Charsets.ISO_8859_1)
        val extra = shadowOf(shown().actions.first().actionIntent).savedIntent
            .getStringExtra(QuestionChoiceReceiver.EXTRA_NOTICE)!!
        for (secret in listOf("Which color?", "Green")) {
            assertFalse(input.contains(secret))
            assertFalse(extra.contains(secret))
        }
    }

    @Test
    fun aQuestionShowsItsOptionsAsButtons() {
        PushService.show(context, push()) { true }

        assertEquals(listOf("Red", "Blue", "Green"), buttons())
    }

    @Test
    fun aBiometricHostOpensThisChatEvenWhenTheDefaultViewIsTerminal() {
        core.setKeyProtection(KeyProtection.BIOMETRIC)
        show()

        assertEquals(listOf(str(R.string.question_notify_open_app)), buttons())
        val target = PushTarget.from(shadowOf(shown().actions.single().actionIntent).savedIntent)
        assertEquals(PushTarget(hostId, "remote/t3", chat = true), target)
    }

    @Test
    fun anOmittedOrOversizedQuestionOnlyOpensTheApp() {
        for (question in listOf(null, PushQuestion("q1", "Color?", listOf("a", "b", "c", "d")))) {
            PushService.show(context, push(question)) { true }
            assertEquals(listOf(str(R.string.question_notify_open_app)), buttons())
        }
    }

    @Test
    fun permissionRequestsNeverHaveQuestionAnswerActions() {
        val push = push()
        val status = push.content as PushContent.Status
        PushService.show(context, push.copy(content = status.copy(status = AgentStatus.Blocked(BlockedReason.PERMISSION, "Allow?")))) { true }
        assertTrue(buttons().isEmpty())
    }

    @Test
    fun aPermissionNotificationPreservesThePromptAndChoiceForAColdWorker() = runBlocking {
        val question = PushQuestion("permission:current", "Allow Write to /repo/file?", listOf("Allow", "Allow always", "Deny"))
        val push = push(question)
        val status = push.content as PushContent.Status
        PushService.show(context, push.copy(content = status.copy(status = AgentStatus.Blocked(BlockedReason.PERMISSION, null)))) { true }
        assertEquals(listOf("Allow", "Allow always", "Deny"), buttons())
        val notice = QuestionNotice.fromJson(shadowOf(shown().actions[2].actionIntent).savedIntent
            .getStringExtra(QuestionChoiceReceiver.EXTRA_NOTICE)!!)!!
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<QuestionReplyWorker>(context)
            .setInputData(QuestionReplyWorker.input(notice, 2)).build().doWork())
        assertEquals(listOf(Triple("remote/t3", "permission:current", listOf(listOf(2u)))), core.backgroundAnswers)
        assertTrue(buttons().isEmpty())
    }

    @Test
    fun tappingAnOptionShowsSendingAndQueuesOnlyOneAnswer() {
        val hold = CompletableDeferred<Unit>()
        core.answerHold = hold
        show()
        val action = shown().actions[1]
        assertFalse("a PLAIN-key question can be answered while locked", action.isAuthenticationRequired)
        action.actionIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        action.actionIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(str(R.string.call_notify_sending, "Blue"), text())
        assertTrue(buttons().isEmpty())
        val works = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("question-reply:$hostId/remote/t3/q1").get()
        assertEquals(1, works.size)
        assertEquals(WorkInfo.State.RUNNING, works.single().state)
        hold.complete(Unit)
    }

    @Test
    fun theWorkerSendsTheQuestionIdAndZeroBasedPickAndShowsCompletion() {
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(listOf(Triple("remote/t3", "q1", listOf(listOf(1u)))), core.backgroundAnswers)
        assertEquals(str(R.string.question_notify_sent, "Blue"), text())
        assertTrue(buttons().isEmpty())
    }

    @Test
    fun aChangedOrAlreadyAnsweredQuestionShowsTheRefusalAndOpensChat() {
        for ((reason, resource) in listOf(AnswerProblem.ANSWERED to R.string.chat_answer_answered,
            AnswerProblem.GONE to R.string.chat_answer_gone)) {
            core.answerFailure = CoreException.AnswerRefused(reason, "stale question")
            runWorker()
            assertEquals(str(resource), text())
            assertEquals(listOf(str(R.string.question_notify_open_app)), buttons())
        }
    }

    @Test
    fun aLostAnswerResultIsShownAsUncertainAndNeverOffersAResend() {
        core.answerFailure = CoreException.AnswerRefused(AnswerProblem.FAILED, "no reply")
        runWorker()
        assertEquals(str(R.string.question_notify_unknown), text())
        assertEquals(listOf(str(R.string.question_notify_open_app)), buttons())
    }

    @Test
    fun anUnreachableHostShowsFailureWithoutQueuingARetry() {
        core.answerFailure = CoreException.Unreachable("connection refused")
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(str(R.string.call_notify_unreachable), text())
        assertEquals(listOf(str(R.string.question_notify_open_app)), buttons())
    }

    @Test
    fun aKeyChangedToBiometricAfterThePushDoesNotSend() = runBlocking {
        val notice = notice()
        core.setKeyProtection(KeyProtection.BIOMETRIC)
        val result = QuestionReplies.answer(context, core, notice, 0, online = true)
        assertEquals(str(R.string.call_notify_key_locked), result.line)
        assertTrue(core.backgroundAnswers.isEmpty())
    }

    @Test
    fun offlineDoesNotSendOrQueueAnything() = runBlocking {
        val result = QuestionReplies.answer(context, core, notice(), 0, online = false)
        assertEquals(str(R.string.call_notify_no_network), result.line)
        assertTrue(core.backgroundAnswers.isEmpty())
    }

    @Test
    fun aLateAnswerCannotReplaceANewerStatusNotification() {
        val old = notice()
        val push = push()
        val status = push.content as PushContent.Status
        PushService.show(context, push.copy(content = status.copy(status = AgentStatus.Working, question = null))) { true }
        QuestionNotifications.post(context, old, QuestionView.Done("old answer"))
        assertEquals(str(R.string.agent_status_working), text())
    }
}
