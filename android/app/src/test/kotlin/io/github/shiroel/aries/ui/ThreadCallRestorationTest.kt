package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.AriesApplication
import io.github.shiroel.aries.HostsViewModel
import io.github.shiroel.aries.MainActivity
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

class ThreadCallRestorationApplication : AriesApplication() {
    override val core = FakeCore().apply {
        ensureKey()
        addHost("Development", Endpoint.Ssh("host.test", 22u, "me"))
        terminals = listOf("Current", "Other").mapIndexed { index, name ->
            TerminalInfo("t${index + 1}", name, "", listOf("claude"), "/repo", "live", AgentStatus.Working)
        }
        callsOf["h1"] = listOf(CallInfo("h1", "notice", 1000uL, "t1", true,
            "Review the result", "No Choice is required", CallUrgency.CHECK, emptyList(), null, true))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ThreadCallRestorationApplication::class, qualifiers = "h1200dp")
class ThreadCallRestorationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ThreadCallRestorationApplication>()
    private var activity: ActivityController<MainActivity>? = null
    private val model get() = ViewModelProvider(activity!!.get())[HostsViewModel::class.java].model

    @After fun close() { activity?.pause()?.stop()?.destroy() }

    // D130: a new ViewModel must restore informational Call acknowledgements from disk,
    // rather than depending on the live model that Activity recreation retains.
    @Test fun closedInformationalCallStaysAcknowledgedAfterLosingTheLiveModel() {
        app.getSharedPreferences("app-guidance", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        ThemeSettings.from(app).chooseLayout(AppLayout.THREAD)
        LastLocationStore.from(app).saveAgent(LastLocation("h1", terminal = "t1", view = AgentView.CHAT))
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitUntil(5_000) { model.calls.calls.size == 1 && model.screen is Screen.Chat }
        compose.onNodeWithText(app.getString(R.string.thread_call_dismiss)).performClick()
        compose.onNodeWithTag("thread-call-panel").assertDoesNotExist()
        val previous = model
        activity!!.pause().stop().destroy()
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitUntil(5_000) { model.calls.calls.size == 1 && model.screen is Screen.Chat }
        compose.runOnIdle { org.junit.Assert.assertNotSame(previous, model) }
        compose.onNodeWithTag("thread-call-panel").assertDoesNotExist()
        compose.runOnIdle { model.openPushedAgent("h1", "t2", openChat = true) }
        compose.onNodeWithTag("thread-waiting-pill").assertDoesNotExist()
        compose.onNodeWithTag("console-menu").performClick()
        compose.onNodeWithText(app.getString(R.string.thread_section_needs)).assertDoesNotExist()
    }
}
