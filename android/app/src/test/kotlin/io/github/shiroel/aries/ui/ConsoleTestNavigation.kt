package io.github.shiroel.aries.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/** Shared user navigation for tests whose assertions concern a destination's behavior. */
fun ComposeContentTestRule.openConsoleDrawer() {
    onNodeWithTag("console-menu").performClick()
}

fun ComposeContentTestRule.openKeyDetails() {
    onNodeWithTag("hosts-key-band").performScrollTo().performClick()
}

fun ComposeContentTestRule.openHostAddForm() {
    onNodeWithTag("hosts-add-toggle").performScrollTo().performClick()
}

fun ComposeContentTestRule.openHostActions(hostId: String) {
    onNodeWithTag("host-menu:$hostId").performScrollTo().performClick()
}

fun ComposeContentTestRule.openHostActionsIfClosed(hostId: String) {
    // Install/Jump tests return to the same row with its expanded actions retained.
    if (onAllNodes(androidx.compose.ui.test.hasTestTag("host-actions:$hostId")).fetchSemanticsNodes().isEmpty()) {
        openHostActions(hostId)
    }
}
