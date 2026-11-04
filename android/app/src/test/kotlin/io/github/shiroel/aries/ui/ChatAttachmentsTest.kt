package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.shiroel.aries.core.*
import io.github.shiroel.aries.R
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatAttachmentsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun replayedUploadsBecomeCardsButOtherPathsStayInTheMessageAndImagesOpenFromTheHost() {
        val core = FakeCore().apply {
            savedView = AgentView.CHAT
            terminals = listOf(TerminalInfo("t7", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Idle))
            addHost("home", Endpoint.Ssh("unused", 22u, "me"))
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        val png = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(70, 120, 170))
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
            output.toByteArray()
        }
        val image = "/home/me/.local/state/herdr-core/default/files/t7/screenshot.png"
        val file = "/custom state's folder/herdr-core/review/files/t9z/report.txt"
        val other = "/repo/files/t7/manual.png"
        val nearMatch = "/home/me/.local/state/herdr-core/default/files/t7/nested/manual.png"
        val invalidId = "/state/herdr-core/default/files/ta/manual.png"
        val traversal = "/state/herdr-core/default/files/t7/../manual.png"
        val reads = mutableListOf<String>()
        core.connection!!.fileBytesReader = { _, path, _, _ ->
            synchronized(reads) { reads += path }
            RemoteFileBytes(png, 0uL, png.size.toULong(), true)
        }
        core.pushChat(ChatSnapshot(listOf(ChatEntry("u1", null, ChatEntryBody.User(
            "Here's the context.\n'$image' '/custom state'\"'\"'s folder/herdr-core/review/files/t9z/report.txt'\n$other\n$nearMatch\n$invalidId\n$traversal",
        ))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment:$image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment:$file").assertExists()
        compose.onNodeWithText(other, substring = true).assertExists()
        compose.onNodeWithText(nearMatch, substring = true).assertExists()
        compose.onNodeWithText(invalidId, substring = true).assertExists()
        compose.onNodeWithText(traversal, substring = true).assertExists()
        compose.onNodeWithText("Here's the context.").assertExists()
        compose.onNodeWithText(image, substring = true).assertDoesNotExist()
        compose.onNodeWithText(file, substring = true).assertDoesNotExist()
        compose.waitUntil(5_000) { synchronized(reads) { image in reads } }
        compose.waitForIdle()
        captureAttachmentScreenshot(compose, "history")
        compose.onNodeWithTag("attachment:$image").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf(image, file), synchronized(reads) { reads.toSet() })
        compose.onNodeWithText("Back").performClick()
        // Searching enters the reading-history renderer, which must use the same cards/body.
        compose.onNodeWithTag("agent-actions").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_search_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("attachment:$image").assertExists()
        compose.onNodeWithText(image, substring = true).assertDoesNotExist()
        captureAttachmentScreenshot(compose, "history-reading")
        core.connection!!.fileReader = { address, path, _, _ ->
            assertEquals("t7", address)
            assertEquals(file, path)
            RemoteFilePage("Attachment contents", 1uL, 1uL, 1uL)
        }
        compose.onNodeWithTag("attachment:$file").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Attachment contents", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}

internal fun captureAttachmentScreenshot(compose: ComposeContentTestRule, name: String) {
    System.getenv("ARIES_ATTACHMENTS_RENDER_DIR")?.let { directory ->
        File(directory).mkdirs()
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
