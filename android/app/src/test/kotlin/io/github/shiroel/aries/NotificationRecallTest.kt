package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.CallUrgency
import io.github.shiroel.aries.core.PushContent
import io.github.shiroel.aries.core.PushNotice
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.CallAnswer
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallInfo
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.PushQuestion
import io.github.shiroel.aries.ui.HostsModel
import io.github.shiroel.aries.ui.Screen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [32])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class NotificationRecallTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val call = PushContent.Call("c1", "t3", true, "Review ready", "Tests pass.", CallUrgency.URGENT, listOf("Merge", "Wait"), 1uL)
    private val notice = CallNotice("h1", "box", call)
    private fun show() = PushService.show(context, PushNotice("h1", "box", call)) { true }
    private fun card() = manager.activeNotifications.single { it.tag == "call:c1" }.notification

    // Regression: a reply finishing after a swipe must not resurrect a ringing Call card.
    @Test
    fun dismissedCallStaysDismissedWhenItsReplyFinishesAndPushIsRedelivered() {
        show()
        card().deleteIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        CallNotifications.post(context, notice, CallView.Done("Delivered"))
        show()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    // Regression: the late duplicate arrives after Done but before unique work becomes finished.
    @Test
    fun duplicateChoiceBroadcastAfterCompletionCannotLeaveSending() {
        show()
        val delayed = shadowOf(card().actions.first().actionIntent).savedIntent
        CallNotifications.post(context, notice, CallView.Done("Delivered"))
        ChoiceReceiver().onReceive(context, delayed)
        assertEquals("Delivered", card().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    // Regression: a duplicate question action can arrive after the worker's final post too.
    @Test
    fun duplicateQuestionBroadcastAfterCompletionCannotLeaveSending() {
        val question = PushQuestion("q1", "Color?", listOf("Blue"))
        PushService.show(context, PushNotice("h1", "box", PushContent.Status("t1", "Agent", null, true,
            AgentStatus.Blocked(BlockedReason.QUESTION, null),
            1uL, null, question, null))) { true }
        val current = manager.activeNotifications.single { it.tag == "t1" }.notification
        val delayed = shadowOf(current.actions.first().actionIntent).savedIntent
        QuestionNotifications.post(context, QuestionNotice("h1", "box", "t1", question), QuestionView.Done("Answered", false))
        QuestionChoiceReceiver().onReceive(context, delayed)
        assertEquals("Answered", manager.activeNotifications.single { it.tag == "t1" }.notification
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    // Regression: a Call resolved in a live feed must retire its card even without a notification reply.
    @Test
    fun anAnswerObservedByTheAppRecallsItsCallAndBlocksDelayedPushes() = runBlocking {
        val app = context as FakeCoreApp
        val host = app.core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        app.core.ensureKey()
        show()
        val owner = ViewModelStore()
        try {
            val viewModel = ViewModelProvider(owner,
                ViewModelProvider.AndroidViewModelFactory(app))[HostsViewModel::class.java]
            val answer = CallAnswer(0u, "Merge", CallAnswerState.DELIVERED, 2uL, null)
            app.core.callsOf[host.id] = listOf(CallInfo(
                host.id, "c1", 1uL, "t3", true, "Review ready", "Tests pass.", CallUrgency.URGENT, listOf("Merge", "Wait"), answer, true))
            viewModel.model.calls.watch(listOf(host), everyHost = true)
            withTimeout(5_000) {
                while (viewModel.model.calls.calls.isEmpty()) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            show()
            assertTrue(manager.activeNotifications.isEmpty())
        } finally {
            owner.clear()
        }
    }

    // Regression: redelivery must not turn an already completed card back into live Choices.
    @Test
    fun aCompletedCallCannotRegainChoicesFromADuplicatePush() {
        show()
        renderNotification(context, card(), "call-ready")
        CallNotifications.post(context, notice, CallView.Done("Delivered"))
        renderNotification(context, card(), "call-done")
        show()
        assertEquals("Delivered", card().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(card().actions.isNullOrEmpty())
    }

    // Regression: summary navigation must select the Host's board without opening or dismissing a child.
    @Test
    fun aHostSummaryOpensOnlyThatHostsAgents() {
        val core = (context as FakeCoreApp).core
        core.addHost("box", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        core.addHost("other", Endpoint.Ssh("10.0.0.3", 22u, "me"))
        core.ensureKey()
        show()
        val intent = shadowOf(manager.activeNotifications.single { it.tag == "host-summary:h1" }.notification.contentIntent).savedIntent
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = HostsModel(core, scope)
        try {
            NotificationGroups.opened(context, intent)
            model.start(PushTarget.from(intent))
            val board = (model.screen as Screen.Agents).board
            assertEquals(listOf("h1"), board.hosts.map { it.id })
            assertTrue(manager.activeNotifications.any { it.tag == "call:c1" })
        } finally {
            scope.coroutineContext[Job]!!.cancel()
        }
    }

    // Regression: an Open app action must retire its own card just like its content tap.
    @Test
    fun openAppActionsRecallBothCallAndQuestionCards() {
        PushService.show(context, PushNotice("h1", "box", call)) { false }
        NotificationGroups.opened(context, shadowOf(card().actions.single().actionIntent).savedIntent)
        val question = PushQuestion("q1", "Color?", listOf("Blue"))
        PushService.show(context, PushNotice("h1", "box", PushContent.Status("t1", "Agent", null, true,
            AgentStatus.Blocked(BlockedReason.QUESTION, null),
            1uL, null, question, null))) { false }
        val current = manager.activeNotifications.single { it.tag == "t1" }.notification
        NotificationGroups.opened(context, shadowOf(current.actions.single().actionIntent).savedIntent)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}

/** Optional render for visual review, without pixel assertions. */
internal fun renderNotification(context: Context, notification: Notification, name: String) {
    val directory = System.getenv("ARIES_NOTIFICATION_RENDER_DIR") ?: return
    java.io.File(directory).mkdirs()
    val parent = android.widget.FrameLayout(context)
    @Suppress("DEPRECATION")
    val view = Notification.Builder.recoverBuilder(context, notification).createBigContentView().apply(context, parent)
    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.AT_MOST))
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    val bitmap = android.graphics.Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE); view.draw(this) }
    java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
}
