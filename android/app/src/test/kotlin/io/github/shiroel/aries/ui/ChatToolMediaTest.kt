package io.github.shiroel.aries.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import java.io.ByteArrayOutputStream
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
class ChatToolMediaTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val png = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            compress(Bitmap.CompressFormat.PNG, 100, out)
            recycle()
        }
        out.toByteArray()
    }

    private fun screenshot(name: String, dialog: Boolean = false) {
        val file = java.io.File("/tmp/aries-mvp-run/lanes/231-shots/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun openChat(tool: ChatEntryBody.Tool): Pair<FakeCore, ChatModel> {
        val core = FakeCore().apply {
            terminals = listOf(TerminalInfo("box/t7", "agent", "", listOf("claude"), "/current", "live", AgentStatus.Idle))
            savedView = AgentView.CHAT
            addHost("home", Endpoint.Ssh("example.invalid", 22u, "me"))
            chatImages["read#0"] = png
        }
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("agent").performClick()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("read", null, tool)), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText(tool.title).fetchSemanticsNodes().isNotEmpty() }
        return core to (model.screen as Screen.Chat).chat
    }

    @Test fun tappingToolImageOpensViewerWhileHeaderOnlyTogglesDetails() {
        val (_, chat) = openChat(ChatEntryBody.Tool(
            "Read", "/repo/shot.png", "{}", ChatToolStatus.DONE, "[image image/png]",
            images = listOf(ChatImage("read#0", "image/png", png.size.toULong(), 160u, 120u, true)),
        ))
        val header = compose.onNode(hasToolAction(compose.activity.getString(R.string.chat_tool_expand_label, "Read")))
        compose.onNodeWithContentDescription("Image").assertDoesNotExist()
        header.performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(4f, 4f)) }
        assertEquals(setOf("read"), chat.expanded)
        compose.onNodeWithContentDescription("Image viewer").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Image").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf("read"), chat.expanded)
        compose.onNodeWithText(compose.activity.getString(R.string.back_button)).performClick()
        header.performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(4f, 4f)) }
        assertEquals(emptySet<String>(), chat.expanded)
        compose.onNodeWithContentDescription("Image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Image viewer").assertDoesNotExist()
        header.performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(4f, 4f)) }
        assertEquals(setOf("read"), chat.expanded)
        compose.onNodeWithContentDescription("Image").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf("read"), chat.expanded)
    }

    // Regression: a cached row with missing bytes must recover when its watch reconnects, without disposing the row.
    @Test fun missingImageBytesRecoverAfterReconnectAndDifferFromDecodeFailure() {
        val body = ChatEntryBody.Tool("Read", "/repo/shot.png", "{}", ChatToolStatus.DONE, null,
            images = listOf(ChatImage("read#0", "image/png", png.size.toULong(), 160u, 120u, true)))
        val (core, _) = openChat(body)
        compose.runOnIdle { core.chatImages.clear() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_tool_expand_label, "Read"))
            .performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(4f, 4f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.chat_image_unavailable)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_image_unreadable)).assertDoesNotExist()
        screenshot("image-unavailable")
        core.pushChat(ChatSnapshot(listOf(ChatEntry("read", null, body)), ChatStatus.RECONNECTING, "cached"))
        compose.waitForIdle()
        compose.runOnIdle { core.chatImages["read#0"] = byteArrayOf(1, 2, 3) }
        core.pushChat(ChatSnapshot(listOf(ChatEntry("read", null, body)), ChatStatus.LIVE, "invalid"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.chat_image_unreadable)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_image_unavailable)).assertDoesNotExist()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("read", null, body)), ChatStatus.RECONNECTING, "retry"))
        compose.waitForIdle()
        compose.runOnIdle { core.chatImages["read#0"] = png }
        core.pushChat(ChatSnapshot(listOf(ChatEntry("read", null, body)), ChatStatus.LIVE, "live"))
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.chat_image)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_image)).performClick()
        // The viewer owns its bytes even if the originating entry disappears.
        core.pushChat(ChatSnapshot(emptyList(), ChatStatus.LIVE, "reset"))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Image viewer").assertExists()
        screenshot("image-viewer", dialog = true)
    }

    @Test fun toolPathUsesRecordedCwdAndBinaryViewerAndMissingFilesShowAnError() {
        val path = "screens/shot with spaces.png"
        val (core, _) = openChat(ChatEntryBody.Tool(
            "Read", path, "{}", ChatToolStatus.DONE, null,
            details = ChatToolDetails(cwd = "/recorded", path = path),
        ))
        core.connection!!.gitFiles = GitChanges("/recorded", listOf(GitFile(path, null, " M", false, true, false)), false, false)
        val reads = mutableListOf<Pair<String, String>>()
        core.connection!!.fileReader = { _, _, _, _ -> error("An image must never use UTF-8 file.read") }
        core.connection!!.fileBytesReader = { address, file, _, _ ->
            synchronized(reads) { reads += address to file }
            if (file.endsWith("missing.pdf")) throw CoreException.Rpc("not_found", "deleted")
            RemoteFileBytes(png, 0uL, png.size.toULong(), true)
        }
        compose.onNodeWithText(path).performFirstLinkClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("box/t7" to "/recorded/screens/shot with spaces.png"), synchronized(reads) { reads.toList() })
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.files_actions_label)).performClick()
        compose.onNodeWithText("Close files").performClick()
        core.pushChat(ChatSnapshot(listOf(ChatEntry("missing", null, ChatEntryBody.Tool(
            "view_image", "/tmp/missing.pdf", "{}", ChatToolStatus.DONE, null,
            details = ChatToolDetails(path = "/tmp/missing.pdf"),
        ))), ChatStatus.LIVE, null))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("/tmp/missing.pdf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/tmp/missing.pdf").performFirstLinkClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.files_preview_failed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Image viewer").assertDoesNotExist()
    }

    @Test fun embeddedToolImagePathOpensEvenWhenTheHostFileWasDeleted() {
        val (core, _) = openChat(ChatEntryBody.Tool(
            "Read", "/tmp/deleted.svg", "{}", ChatToolStatus.DONE, "[image image/png]",
            images = listOf(ChatImage("read#0", "image/png", png.size.toULong(), 160u, 120u, true)),
            details = ChatToolDetails(path = "/tmp/deleted.svg"),
        ))
        core.connection!!.fileBytesReader = { _, _, _, _ -> error("Embedded image must not be downloaded again") }
        compose.onNodeWithText("/tmp/deleted.svg").performFirstLinkClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.files_actions_label)).performClick()
        compose.onNodeWithText("Close files").performClick()
        compose.onNode(hasToolAction(compose.activity.getString(R.string.chat_tool_expand_label, "Read")))
            .performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(4f, 4f)) }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_tool_path, "/tmp/deleted.svg")).performFirstLinkClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().isNotEmpty() }
    }
}
