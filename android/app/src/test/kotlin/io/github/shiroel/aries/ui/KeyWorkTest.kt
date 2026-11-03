package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every core call that opens the SSH key runs in the model's key-work
 * context: a biometric key cannot be opened on the main thread, so a call
 * left on it fails with "biometric unlock on the main thread".
 */
@RunWith(RobolectricTestRunner::class)
class KeyWorkTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val claude = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)

    private fun show(core: FakeCore) {
        core.terminals = listOf(claude)
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember {
                HostsModel(core, scope, keyWork = Dispatchers.Unconfined + inKeyWork.asContextElement(true))
            }
            MaterialTheme { AriesApp(model) }
        }
    }

    @Test
    fun browsingTheHostAndOpeningAChatSignInOffTheMainThread() {
        val core = FakeCore()
        core.pushPaired["h1"] = "https://relay.example"
        show(core)
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
        compose.waitForIdle()

        assertTrue(core.chatsOpened.isNotEmpty())
        assertEquals(emptyList<String>(), core.offKeyWork)
    }

    @Test
    fun theAgentListSignsInOffTheMainThread() {
        val core = FakeCore()
        show(core)
        compose.onNodeWithText(str(R.string.agents_button)).performClick()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), core.offKeyWork)
    }
}
