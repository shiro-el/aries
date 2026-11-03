package io.github.shiroel.aries.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Seam 3: search controls send only search operations through the attached core. */
@RunWith(RobolectricTestRunner::class)
class TerminalSearchTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun open(): FakeTerminalView {
        val core = FakeCore().apply {
            terminals = listOf(TerminalInfo("t1", "build", "", listOf("make"), "/repo", "live", null, false, 0uL))
        }
        core.addHost("home", Endpoint.Ssh("10.0.0.2", 22u, "me"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText("home").performClick()
        compose.onNodeWithText("build").performClick()
        compose.waitForIdle()
        return core.connection!!.views.single()
    }

    private fun results(view: FakeTerminalView, current: UInt, scrolled: UInt) {
        view.push(TerminalUpdate.Screen(ScreenFrame(
            80u, 4u, true, emptyList(), null, null, scrolled, 200u,
            TerminalSearch(2u, current, listOf(TerminalSearchHighlight(0u, 0u, 5u, true))),
        )))
        compose.waitForIdle()
    }

    @Test
    fun searchesNavigatesChangesOptionsAndClosesWithoutTyping() {
        val view = open()
        compose.onNodeWithText(str(R.string.terminal_search_button)).performClick()
        compose.onNodeWithTag("terminal-search-query").performTextInput("error")
        compose.waitUntil(5000) { view.searches.any { it.first == "error" } }
        assertEquals(Triple("error", false, false), view.searches.last())
        results(view, 0u, 180u)
        compose.onNodeWithText(str(R.string.terminal_search_count, 1, 2)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.terminal_search_next)).performClick()
        compose.waitUntil(5000) { view.searchMoves.isNotEmpty() }
        assertEquals(false, view.searchMoves.last())
        results(view, 1u, 0u)
        compose.onNodeWithText(str(R.string.terminal_search_count, 2, 2)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.terminal_search_previous)).performClick()
        compose.waitUntil(5000) { view.searchMoves.lastOrNull() == true }

        compose.onNodeWithText(str(R.string.terminal_search_case)).performClick()
        compose.waitUntil(5000) { view.searches.lastOrNull() == Triple("error", true, false) }
        compose.onNodeWithText(str(R.string.terminal_search_regex)).performClick()
        compose.waitUntil(5000) { view.searches.lastOrNull() == Triple("error", true, true) }
        compose.onNodeWithText(str(R.string.terminal_search_close)).performClick()
        compose.waitUntil(5000) { view.searches.lastOrNull()?.first == "" }
        assertTrue(view.sent.isEmpty())
        assertTrue(view.resizes.isEmpty())
        assertTrue(view.options.observe)
    }

    @Test
    fun invalidRegexExplainsTheErrorAndNoMatchesDisablesNavigation() {
        val view = open()
        compose.onNodeWithText(str(R.string.terminal_search_button)).performClick()
        compose.onNodeWithText(str(R.string.terminal_search_regex)).performClick()
        compose.onNodeWithTag("terminal-search-query").performTextInput("[")
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(str(R.string.terminal_search_invalid)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(str(R.string.terminal_search_next)).assertIsNotEnabled()
        compose.onNodeWithTag("terminal-search-query").performTextReplacement("missing")
        compose.waitUntil(5000) { view.searches.lastOrNull()?.first == "missing" }
        view.push(TerminalUpdate.Screen(ScreenFrame(80u, 4u, true, emptyList(), null, null,
            search = TerminalSearch(0u, null, emptyList()))))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminal_search_no_matches)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.terminal_search_previous)).assertIsNotEnabled()
        assertTrue(view.searchMoves.isEmpty())
        assertTrue(view.sent.isEmpty())
    }

    @Test
    fun finishedTerminalKeepsItsSearchableVtUntilBackDetaches() {
        val view = open()
        view.push(TerminalUpdate.Exited(TerminalExit("exited", 0, null)))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.terminal_search_button)).performClick()
        compose.onNodeWithTag("terminal-search-query").performTextInput("error")
        compose.waitUntil(5000) { view.searches.any { it.first == "error" } }
        results(view, 0u, 180u)
        compose.onNodeWithText(str(R.string.terminal_search_count, 1, 2)).assertExists()
        assertTrue(!view.detached)
        compose.onNodeWithText(str(R.string.back_button)).performClick()
        compose.waitForIdle()
        assertTrue(view.detached)
    }
}
