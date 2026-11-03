package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Real canvas pixels and the phone grid, with only the Host connection faked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalDisplayRendererTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var model: HostsModel

    private fun open(): Pair<TerminalDisplaySettings, FakeCore> {
        val preferences = context.getSharedPreferences("terminal-render-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = TerminalDisplaySettings(preferences)
        val core = FakeCore()
        core.terminals = listOf(TerminalInfo("t1", "claude", "", listOf("claude"), "/repo", "live", AgentStatus.Working, false, 0uL))
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, terminalDisplay = settings)
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("claude").performClick()
        return settings to core
    }

    private fun screen() = compose.onNodeWithTag("terminal-screen").captureToImage()

    @Test
    fun changingFontSizeResizesTheTypingGridAndChangingFontRedrawsTheText() {
        val (settings, core) = open()
        core.connection!!.views.single().push(TerminalUpdate.Screen(ScreenFrame(
            8u, 2u, true,
            listOf(ScreenLine(0u, listOf(CellRun(0u, 4u, "0lMm", false, null, null, false, false, false, false, false, false)))),
            null, null,
        )))
        compose.runOnIdle { (model.screen as Screen.Terminal).viewer.startTyping() }
        compose.waitForIdle()
        val viewer = (model.screen as Screen.Terminal).viewer
        val smallGrid = viewer.phoneGrid!!
        compose.runOnIdle { settings.chooseFontSize(28) }
        compose.runOnIdle {
            val largeGrid = viewer.phoneGrid!!
            assertTrue("larger text leaves fewer columns", largeGrid.first < smallGrid.first)
            assertTrue("larger text leaves fewer rows", largeGrid.second < smallGrid.second)
        }
        val system = screen().toPixelMap()
        compose.runOnIdle { settings.chooseFont(TerminalFont.JETBRAINS_MONO) }
        val jetbrains = screen().toPixelMap()
        assertTrue("the selected font changes the rendered glyphs", (0 until system.height).any { y ->
            (0 until system.width).any { x -> system[x, y] != jetbrains[x, y] }
        })
    }

    @Test
    fun contrastToggleRepaintsExistingTextWithoutWaitingForAnotherHostFrame() {
        val (settings, core) = open()
        core.connection!!.views.single().push(TerminalUpdate.Screen(ScreenFrame(
            8u, 2u, true,
            listOf(ScreenLine(0u, listOf(CellRun(0u, 4u, "MMMM", false, 0x25272Cu, 0x461E20u, false, false, false, false, false, false)))),
            null, null,
        )))
        assertTrue("original dark text", !hasLightText(screen()))
        compose.runOnIdle { settings.chooseMinimumContrast(true) }
        assertTrue("near-white replacement is drawn", hasLightText(screen()))
        compose.runOnIdle { settings.chooseMinimumContrast(false) }
        assertTrue("program colors return", !hasLightText(screen()))
    }

    @Test
    fun graphicsKeepTheirColorBesideCorrectedTextInTheSameCoreRun() {
        val (settings, core) = open()
        compose.runOnIdle { settings.chooseMinimumContrast(true) }
        for (text in listOf("██MMMM", "MMMM██")) {
            core.connection!!.views.single().push(TerminalUpdate.Screen(ScreenFrame(
                8u, 2u, true,
                listOf(ScreenLine(0u, listOf(CellRun(0u, 6u, text, false, 0x25272Cu, 0x461E20u, false, false, false, false, false, false)))),
                null, null,
            )))
            val image = screen()
            assertTrue("text is corrected in $text", hasLightText(image))
            val pixels = image.toPixelMap()
            assertTrue("blocks keep the original color in $text", (0 until pixels.height).any { y ->
                (0 until pixels.width).any { x -> pixels[x, y] == androidx.compose.ui.graphics.Color(0xFF25272C) }
            })
        }
    }

    private fun hasLightText(image: ImageBitmap): Boolean {
        val pixels = image.toPixelMap()
        return (0 until pixels.height).any { y -> (0 until pixels.width).any { x ->
            val color = pixels[x, y]
            color.red > 0.8f && color.green > 0.8f && color.blue > 0.8f
        } }
    }
}
