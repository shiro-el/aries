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
import io.github.shiroel.aries.ui.Screen
import java.io.ByteArrayInputStream
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
    @Test fun agentPickerAndUriGrantSurviveProcessDeathAndAreClearedAfterSelection() {
        val app = ApplicationProvider.getApplicationContext<ConfigurationChangeApplication>()
        app.getSharedPreferences("app-guidance", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("update_url", "").commit()
        app.fake.terminals = listOf(TerminalInfo("t1", "Review", "", listOf("claude"), "/repo", "live", AgentStatus.Idle))
        val uri = Uri.parse("content://shared/after-process-death")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(2, 6, 1)))
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
        first.pause().saveInstanceState(saved).stop().destroy()
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
                (secondModel.screen as? Screen.Chat)?.chat?.attachments?.size == 1
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
