package io.github.shiroel.aries

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.ui.registerAttachmentInput
import io.github.shiroel.aries.ui.Screen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A fresh Activity/ViewModel receives only parceled saved state, as after process death. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ConfigurationChangeApplication::class, qualifiers = "h1200dp")
class ShareSavedStateTest {
    @get:Rule val compose = createEmptyComposeRule()

    // D100: consuming the Activity entry must not discard the share while the Agent picker owns it.
    @Test fun agentPickerAndUriGrantSurviveProcessDeathAndAreClearedAfterConsumption() = restoreShare(false)

    // D19: selecting an Agent must not drop the envelope before Chat consumes it.
    @Test fun selectedAgentBeforeChatConsumptionRetainsTheShareAcrossProcessDeath() = restoreShare(true)

    // D19: retaining an envelope during upload must not replay it into the retained Chat on rotation.
    @Test fun rotationDuringUploadKeepsTheSameChatAndDoesNotReplaySharedText() {
        val app = ApplicationProvider.getApplicationContext<ConfigurationChangeApplication>()
        app.getSharedPreferences("app-guidance", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        app.fake.terminals = listOf(TerminalInfo("t1", "Review", "", listOf("claude"), "/repo", "live", AgentStatus.Idle))
        val uri = Uri.parse("content://shared/rotation")
        registerAttachmentInput(app, uri, byteArrayOf(2, 9, 2))
        val incoming = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, "Keep once")
        val activity = Robolectric.buildActivity(MainActivity::class.java, incoming).setup()
        try {
            compose.waitForIdle()
            val model = ViewModelProvider(activity.get())[HostsViewModel::class.java].model
            compose.waitUntil(5_000) { (model.screen as? Screen.Agents)?.board?.agents?.any { it.live } == true }
            val line = app.fake.connection!!
            line.uploadHold = kotlinx.coroutines.CompletableDeferred()
            compose.onNodeWithTag("agent-row:h1:t1").performClick()
            compose.waitUntil(5_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); line.uploadStarted == 1 }
            val chat = (model.screen as Screen.Chat).chat
            assertEquals("Keep once", chat.draft)
            assertNotNull(model.pendingShareIntent)
            activity.recreate()
            compose.waitForIdle()
            assertSame(model, ViewModelProvider(activity.get())[HostsViewModel::class.java].model)
            assertTrue("Rotation must retain the open Chat", model.screen is Screen.Chat)
            assertSame(chat, (model.screen as Screen.Chat).chat)
            assertEquals("Keep once", chat.draft)
            assertEquals(1, line.uploadStarted)
            line.uploadHold!!.complete(Unit)
            compose.waitUntil(5_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); !chat.uploading }
            assertNull(model.pendingShareIntent)
        } finally {
            app.fake.connection?.uploadHold?.complete(Unit)
            activity.pause().stop().destroy()
        }
    }

    private fun restoreShare(selectBeforeSaving: Boolean) {
        val app = ApplicationProvider.getApplicationContext<ConfigurationChangeApplication>()
        app.getSharedPreferences("app-guidance", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        app.fake.terminals = listOf(TerminalInfo("t1", "Review", "", listOf("claude"), "/repo", "live", AgentStatus.Idle))
        val uri = Uri.parse("content://shared/after-process-death")
        registerAttachmentInput(app, uri, byteArrayOf(2, 6, 1))
        val incoming = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, "Inspect after restart")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        incoming.clipData = ClipData.newRawUri("shared", uri)
        val first = Robolectric.buildActivity(MainActivity::class.java, incoming).setup()
        compose.waitForIdle()
        val firstModel = ViewModelProvider(first.get())[HostsViewModel::class.java].model
        assertTrue(firstModel.screen is Screen.Agents)
        compose.onNodeWithText(app.getString(R.string.attachments_choose_agent)).assertExists()
        val saved = Bundle()
        if (selectBeforeSaving) {
            compose.waitUntil(5_000) { (firstModel.screen as? Screen.Agents)?.board?.agents?.any { it.live } == true }
            // Save within the same UI turn, before Chat's LaunchedEffect can consume the content.
            compose.runOnIdle {
                val agents = firstModel.screen as Screen.Agents
                firstModel.openAgent(agents.board, agents.board.agents.first())
                assertTrue(firstModel.screen is Screen.Chat)
                assertNotNull((firstModel.screen as Screen.Chat).chat.pendingShare)
                first.pause().saveInstanceState(saved)
            }
            first.stop().destroy()
        } else first.pause().saveInstanceState(saved).stop().destroy()
        val restoredState = parcel(saved)
        @Suppress("DEPRECATION")
        val retained = restoredState.getParcelable<Intent>("pending-share-intent")
        assertNotNull("Agent selection must retain the original share including its grants", retained)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, retained!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(uri, retained.clipData!!.getItemAt(0).uri)

        // Neither the original Activity, ViewModel nor incoming Activity intent is reused.
        val second = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN))
            .create(restoredState).start().resume().visible()
        try {
            compose.waitForIdle()
            val secondModel = ViewModelProvider(second.get())[HostsViewModel::class.java].model
            assertNotSame(firstModel, secondModel)
            assertTrue(secondModel.screen is Screen.Agents)
            compose.onNodeWithText(app.getString(R.string.attachments_choose_agent)).assertExists()
            compose.waitUntil(5_000) { (secondModel.screen as? Screen.Agents)?.board?.agents?.any { it.live } == true }
            compose.onNodeWithTag("agent-row:h1:t1").performClick()
            compose.waitUntil(5_000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                (secondModel.screen as? Screen.Chat)?.chat?.let { it.attachments.size == 1 && !it.uploading } == true
            }
            compose.onNodeWithContentDescription(app.getString(R.string.chat_input_label)).assertTextEquals("Inspect after restart")
            assertArrayEquals(byteArrayOf(2, 6, 1), app.fake.connection!!.uploads.single().third)
            assertTrue(app.fake.connection!!.typed.isEmpty())
            val consumed = Bundle()
            second.pause().saveInstanceState(consumed)
            @Suppress("DEPRECATION")
            assertNull(parcel(consumed).getParcelable<Intent>("pending-share-intent"))
        } finally {
            second.stop().destroy()
        }
    }

    private fun parcel(state: Bundle): Bundle {
        val output = Parcel.obtain()
        val input = Parcel.obtain()
        try {
            output.writeBundle(state)
            val bytes = output.marshall()
            input.unmarshall(bytes, 0, bytes.size)
            input.setDataPosition(0)
            return input.readBundle(MainActivity::class.java.classLoader)!!
        } finally {
            input.recycle()
            output.recycle()
        }
    }
}
