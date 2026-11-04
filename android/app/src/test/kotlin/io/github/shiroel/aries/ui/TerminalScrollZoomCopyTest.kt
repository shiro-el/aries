package io.github.shiroel.aries.ui

import androidx.compose.ui.test.onAllNodesWithContentDescription

import androidx.compose.ui.test.onNodeWithContentDescription

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalInfo
import io.github.shiroel.aries.core.TerminalUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Seam 3: reading back, zooming and copying in an observed Terminal, against a fake core. */
// Real text metrics, so cells have their real size and a touch lands on a known cell.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class TerminalScrollZoomCopyTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private val build = TerminalInfo("t2", "build", "", listOf("make"), "/repo", "live", null, false, 0uL)
    private val opened = mutableListOf<String>()

    private fun run(col: Int, text: String, link: String? = null) =
        CellRun(col.toUShort(), text.length.toUShort(), text, false, null, null, false, false, false, false, false, false, link)

    private fun frame(rows: List<List<CellRun>>, scrolled: Int = 0, history: Int = 500) = TerminalUpdate.Screen(
        ScreenFrame(
            COLS.toUShort(), rows.size.toUShort(), true,
            rows.mapIndexed { i, runs -> ScreenLine(i.toUShort(), runs) },
            null, null, scrolled.toUInt(), history.toUInt(),
        ),
    )

    private fun text(vararg rows: String) = frame(rows.map { listOf(run(0, it)) })

    /** Opens the "build" Terminal and returns its fake view. */
    private fun open(): FakeTerminalView {
        val core = FakeCore().apply { terminals = listOf(build) }
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        val uris = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            CompositionLocalProvider(LocalUriHandler provides uris) { MaterialTheme { AriesApp(model) } }
        }
        compose.onNodeWithText("home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("build").performClick()
        compose.waitForIdle()
        return core.connection!!.views.single()
    }

    private val screen get() = compose.onNodeWithTag("terminal-screen")

    /** The middle of column [col] on the top row. */
    private fun cell(col: Int): Offset {
        val width = screen.fetchSemanticsNode().size.width
        return Offset((col + 0.5f) * width / COLS, 1f)
    }

    private fun clipboard(): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun attachesWithScrollbackAndSwipingDownReadsBackThenReturnsToLive() {
        val view = open()
        assertTrue("asks for the scrollback", view.options.history > 0u)
        view.push(text("$ make", "done"))
        compose.waitForIdle()

        screen.performTouchInput { swipeDown() }
        compose.waitForIdle()
        val back = view.scrolls.last()
        assertTrue("scrolled into the scrollback: ${view.scrolls}", back > 0u)

        // The core answers with the older lines.
        view.push(frame(listOf(listOf(run(0, "older output"))), scrolled = back.toInt()))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminal_scrolled_back, back.toInt())).assertExists()

        val beforeLatest = view.scrolls.size
        compose.onNodeWithContentDescription(str(R.string.terminal_back_to_live)).performClick()
        compose.waitForIdle()
        assertEquals(0u, view.scrolls.last())
        assertTrue("return to live moves through scrollback instead of jumping",
            view.scrolls.drop(beforeLatest).any { it > 0u && it < back })

        // Swiping up from the live screen does not scroll below it.
        view.push(text("$ make", "done"))
        compose.waitForIdle()
        val before = view.scrolls.size
        screen.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(view.scrolls.drop(before).all { it == 0u })
        compose.onAllNodesWithContentDescription(str(R.string.terminal_back_to_live)).assertCountEquals(0)
    }

    @Test
    fun pinchingZoomsTheViewOnlyAndResetGoesBack() {
        val view = open()
        view.push(text("$ make", "done"))
        compose.waitForIdle()

        screen.performTouchInput {
            pinch(center - Offset(10f, 0f), center - Offset(150f, 0f), center + Offset(10f, 0f), center + Offset(150f, 0f))
        }
        compose.waitForIdle()
        val zoomed = compose.onAllNodesWithText(str(R.string.terminal_zoom_reset, 0).substringBefore("0"), substring = true)
        zoomed.assertCountEquals(1)
        zoomed[0].performClick()
        compose.waitForIdle()
        zoomed.assertCountEquals(0)

        // Zooming is the phone's business: still the one observing view, the desktop's grid.
        assertTrue(view.options.observe)
        compose.onNodeWithText(str(R.string.terminal_observing, "t2", COLS, 2)).assertExists()
        assertTrue("no scrolling from a pinch", view.scrolls.isEmpty())
    }

    @Test
    fun longPressSelectsAWordAndCopiesIt() {
        val view = open()
        view.push(text("hello world"))
        compose.waitForIdle()

        screen.performTouchInput { longClick(cell(1)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.terminal_copy)).performClick()
        compose.waitForIdle()
        assertEquals("hello", clipboard())
        compose.onAllNodesWithContentDescription(str(R.string.terminal_copy)).assertCountEquals(0)
    }

    @Test
    fun longPressOnAPrintedUrlOpensOrCopiesIt() {
        val view = open()
        view.push(text("see https://example.com/a?b=1. ok"))
        compose.waitForIdle()

        screen.performTouchInput { longClick(cell(8)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.terminal_open_link)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://example.com/a?b=1"), opened)

        screen.performTouchInput { longClick(cell(8)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.terminal_copy_link)).performClick()
        compose.waitForIdle()
        assertEquals("https://example.com/a?b=1", clipboard())
    }

    @Test
    fun longPressOnAHyperlinkUsesItsTarget() {
        val view = open()
        view.push(frame(listOf(listOf(run(0, "see"), run(4, "the run", link = "https://ci.example/run/7")))))
        compose.waitForIdle()

        screen.performTouchInput { longClick(cell(5)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(str(R.string.terminal_open_link)).performClick()
        assertEquals(listOf("https://ci.example/run/7"), opened)
    }

    private companion object {
        const val COLS = 120
    }
}
