package io.github.shiroel.aries.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DrawerNavigationTest {
    @get:Rule val compose = createComposeRule()

    // D80: a competing drawer close must not discard the destination the user already tapped.
    @Test fun cancellingTheCloseAnimationStillRunsTheTappedAction() {
        lateinit var navigate: (() -> Unit) -> Unit
        val visited = mutableListOf<String>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(FakeCore(), scope) }
            ConsoleShell(model, drawerContent = { navigate = it }, content = {
                val open = LocalConsoleMenu.current!!
                Button(onClick = open) { Text("Open") }
            })
        }
        compose.onNodeWithText("Open").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            navigate { visited += "first" }
            navigate { visited += "second" }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(listOf("first", "second"), visited.sorted()) }
    }
}
