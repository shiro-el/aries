package io.github.shiroel.aries

import android.content.Context
import android.content.ComponentName
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Android widget -> core seam: render the actual RemoteViews supplied to a launcher. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class)
class HomeWidgetsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val first = Host("h1", "Laptop", Endpoint.Ssh("one", 22u, "me"), null)
    private val second = Host("h2", "Desktop", Endpoint.Ssh("two", 22u, "me"), null)
    private val now = 600_000L
    private fun agent(id: String, status: AgentStatus, parent: String? = null) = TerminalInfo(
        id, id, "", emptyList(), null, "live", agentStatus = status, parent = parent,
        needsAttention = parent == null && (status is AgentStatus.Blocked || status is AgentStatus.Error),
        statusMs = 300_000uL,
    )

    @Test fun compactWidgetCountsAttentionAcrossHostsAndShowsOldestFetchAge() {
        val states = listOf(
            LastKnownState(first.id, listOf(agent("blocked", AgentStatus.Blocked(null, null)),
                agent("child", AgentStatus.Error(null), "blocked")), 480_000uL),
            LastKnownState(second.id, listOf(agent("error", AgentStatus.Error(null)),
                agent("working", AgentStatus.Working)), 420_000uL),
        )
        val view = HomeWidgets.render(context, listOf(first, second), states, expanded = false, nowMillis = now)
            .apply(context, FrameLayout(context))
        assertEquals("Needs you · 2", view.findViewById<TextView>(R.id.widget_count).text.toString())
        assertEquals("3 min ago", view.findViewById<TextView>(R.id.widget_age).text.toString())
        assertEquals("Refresh", view.findViewById<android.view.View>(R.id.widget_refresh).contentDescription)
    }

    @Test fun expandedWidgetShowsTopThreeAgentsAndEachTapKeepsItsWholeAddress() {
        val states = listOf(
            LastKnownState(first.id, listOf(agent("working", AgentStatus.Working),
                agent("approve", AgentStatus.Blocked(null, null)), agent("done", AgentStatus.Done)), 480_000uL),
            LastKnownState(second.id, listOf(agent("machine/t7", AgentStatus.Error(null)).copy(name = "remote", statusMs = 360_000uL)), 420_000uL),
        )
        val view = HomeWidgets.render(context, listOf(first, second), states, expanded = true, nowMillis = now)
            .apply(context, FrameLayout(context))
        val texts = textViews(view).map { it.text.toString() }
        assertEquals(listOf("remote", "approve", "done"), texts.filter { it in listOf("remote", "approve", "done", "working") })
        assertTrue(texts.contains("Error · Desktop · 4m"))
        assertTrue(texts.contains("Blocked · Laptop · 5m"))
        assertTrue(texts.contains("Done · Laptop · 5m"))
        assertTrue(texts.contains("3 min ago"))
        val expected = listOf(PushTarget(second.id, "machine/t7"), PushTarget(first.id, "approve"), PushTarget(first.id, "done"))
        listOf("remote", "approve", "done").zip(expected).forEach { (name, target) ->
            (textViews(view).single { it.text.toString() == name }.parent as View).performClick()
            assertEquals(target, PushTarget.from(shadowOf(context as android.app.Application).nextStartedActivity))
        }
    }

    @Test fun refreshOnEitherSizeQueuesTheSharedAllHostPull() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        listOf(false, true).forEach { expanded ->
            val view = HomeWidgets.render(context, listOf(first, second), emptyList(), expanded, now)
                .apply(context, FrameLayout(context))
            view.findViewById<View>(R.id.widget_refresh).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val requests = WorkManager.getInstance(context).getWorkInfosForUniqueWork("last-known-state:refresh:all").get()
            assertEquals(1, requests.size)
            assertEquals(androidx.work.NetworkType.CONNECTED, requests.single().constraints.requiredNetworkType)
        }
    }

    @Test fun installedWidgetsRenderPersistedStateThenFollowLocalChangesWithoutPulling() = runBlocking {
        val core = (context as FakeCoreApp).core
        core.addHost(first.name, first.endpoint)
        core.addHost(second.name, second.endpoint)
        core.storedStates[first.id] = LastKnownState(first.id, listOf(agent("approve", AgentStatus.Blocked(null, null))), 480_000uL)
        core.storedStates[second.id] = LastKnownState(second.id, emptyList(), 420_000uL)
        val manager = shadowOf(AppWidgetManager.getInstance(context))
        manager.addBoundWidget(78, AppWidgetProviderInfo().apply { provider = ComponentName(context, WaitingHomeWidget::class.java) })
        manager.addBoundWidget(79, AppWidgetProviderInfo().apply { provider = ComponentName(context, AgentsHomeWidget::class.java) })
        val observation = HomeWidgets.observe(context, core, this)
        suspend fun awaitCount(expected: String) = withTimeout(5_000) {
            while (listOf(78, 79).any { manager.getViewFor(it)?.findViewById<TextView>(R.id.widget_count)?.text?.toString() != expected }) delay(10)
        }
        try {
            awaitCount("Needs you · 1")
            assertTrue(core.snapshotsRequested.isEmpty())
            core.terminals = listOf(agent("working", AgentStatus.Working))
            core.snapshot(first.id)
            awaitCount("Needs you · 0")
            // Robolectric applies RemoteViews directly on the publishing thread;
            // wait for the rows too, rather than observing its in-progress apply.
            withTimeout(5_000) {
                while (textViews(manager.getViewFor(79)).none { it.text.toString() == "working" }) delay(10)
            }
            assertTrue(textViews(manager.getViewFor(79)).any { it.text.toString() == "working" })
            assertFalse(textViews(manager.getViewFor(79)).any { it.text.toString() == "approve" })
        } finally {
            observation.cancelAndJoin()
        }
    }

    @Test fun neverFetchedHostIsDistinctFromAFetchedEmptyList() {
        listOf(false, true).forEach { expanded ->
            fun rendered(states: List<LastKnownState>) = HomeWidgets.render(context, listOf(first), states, expanded, now)
                .apply(context, FrameLayout(context))
            val unknown = rendered(emptyList())
            assertEquals("Needs you · —", unknown.findViewById<TextView>(R.id.widget_count).text.toString())
            assertEquals("Awaiting Host state", unknown.findViewById<TextView>(R.id.widget_age).text.toString())
            val empty = rendered(listOf(LastKnownState(first.id, emptyList(), 600_001uL)))
            assertEquals("Needs you · 0", empty.findViewById<TextView>(R.id.widget_count).text.toString())
            assertEquals("just now", empty.findViewById<TextView>(R.id.widget_age).text.toString())
            if (expanded) assertEquals(View.VISIBLE, empty.findViewById<View>(R.id.widget_empty).visibility)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun textFitsCompactAndExpandedLauncherBounds() {
        val state = LastKnownState(first.id, listOf(agent("approve", AgentStatus.Blocked(null, null)),
            agent("working", AgentStatus.Working), agent("done", AgentStatus.Done)), 480_000uL)
        listOf(Triple(false, 180, 80), Triple(true, 280, 140)).forEach { (expanded, width, height) ->
            val view = HomeWidgets.render(context, listOf(first), listOf(state), expanded, now)
                .apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            val w = (width * density).toInt()
            val h = (height * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            textViews(view).filter { it.visibility == View.VISIBLE }.forEach { text ->
                assertTrue("${text.text} clipped in ${width}×${height}", text.height > 0 && text.top >= 0 && text.bottom <= (text.parent as View).height)
            }
        }
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
