package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatSnapshot
import io.github.shiroel.aries.core.ChatStatus
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.BlockedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam 3: the Chat cache's settings, its per-Host clearing, and the Chat's use of it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class ChatCacheTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val claude = TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL)
    private val cached = ChatSnapshot(
        listOf(ChatEntry("u1", null, ChatEntryBody.User("read me offline"))),
        ChatStatus.RECONNECTING,
        "c:1",
    )

    private lateinit var model: HostsModel

    private fun show(core: FakeCore) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    private fun openChat(core: FakeCore) {
        core.terminals = listOf(claude)
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        show(core)
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.agent_view_chat)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theRetentionPeriodIsTheUsersChoice() {
        val core = FakeCore()
        show(core)
        compose.openConsoleDrawer()
        compose.onNodeWithText(str(R.string.settings_title)).performClick()
        scrollTo(str(R.string.chat_cache_days, 7))
        compose.onNodeWithText(str(R.string.chat_cache_title)).assertExists()

        compose.onNodeWithText(str(R.string.chat_cache_days, 7)).performClick()
        compose.waitForIdle()
        assertEquals(7u, core.retentionDays)
        assertEquals(7, model.chatCache.retentionDays)
    }

    @Test
    fun clearsOneHostsCachedChats() {
        val core = FakeCore()
        val home = core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        val work = core.addHost("work", Endpoint.Ssh("work.example", 22u, "dev"))
        core.cachedChats[home.id to "t1"] = cached
        core.cachedChats[work.id to "t1"] = cached
        show(core)

        compose.openHostActions("h1")
        compose.onNodeWithContentDescription(str(R.string.chat_cache_clear_label, "home")).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(setOf(work.id to "t1"), core.cachedChats.keys)
        scrollTo(str(R.string.chat_cache_cleared, "home"))
        compose.onNodeWithText(str(R.string.chat_cache_cleared, "home")).assertExists()
    }

    @Test
    fun theChatShowsItsCacheWhileTheHostIsUnreachable() {
        val core = FakeCore()
        openChat(core)
        core.pushChat(cached)
        compose.waitUntil(5000) { compose.onAllNodesWithText("read me offline").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("read me offline").assertExists()
        compose.onNodeWithText(str(R.string.chat_status_reconnecting)).assertExists()
    }

    @Test
    fun aChatThatCannotStartStillShowsItsCache() {
        val core = FakeCore()
        core.failChatWith = CoreException.KeyLocked()
        core.cachedChats["h1" to "t1"] = cached
        openChat(core)
        compose.waitUntil(5000) { compose.onAllNodesWithText("read me offline").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("read me offline").assertExists()
    }

    @Test
    fun networkChangesAndComingBackReachTheOpenChat() {
        val core = FakeCore()
        openChat(core)

        compose.runOnIdle { model.networkChanged() }
        assertEquals(1, core.chatReconnects)
        compose.runOnIdle { model.foregrounded() }
        assertEquals(1, core.chatRetries)
    }

    @Test
    fun theChatUnderATerminalOpenedFromItReconnectsOnceToo() {
        val core = FakeCore()
        openChat(core)
        core.push(listOf(claude.copy(agentStatus = AgentStatus.Blocked(BlockedReason.PERMISSION, null), needsAttention = true)))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminal_open_button)).performClick()
        compose.waitForIdle()
        val first = core.connection!!

        compose.runOnIdle { model.networkChanged() }
        compose.waitForIdle()
        // The Chat's own line once, and the Host line once (one new connection).
        assertEquals(1, core.chatReconnects)
        assertTrue(first.disconnected)
        val second = core.connection!!
        assertNotSame(first, second)
        compose.runOnIdle { model.foregrounded() }
        assertEquals(1, core.chatRetries)
        assertSame(second, core.connection)
    }
}
