package io.github.shiroel.aries.ui

import androidx.compose.ui.test.junit4.ComposeTestRule

// Compose idleness does not include the journal's IO dispatcher. Observe the
// core boundary before asserting exact delivery or advancing the fake Agent turn.
internal fun ComposeTestRule.waitForTypedMessages(connection: FakeConnection, count: Int) {
    waitUntil(5_000) { runOnIdle { connection.typed.size >= count } }
}

internal fun ComposeTestRule.waitForSendAttempts(connection: FakeConnection, count: Int) {
    waitUntil(5_000) { runOnIdle { connection.sendListeners.size >= count } }
}
