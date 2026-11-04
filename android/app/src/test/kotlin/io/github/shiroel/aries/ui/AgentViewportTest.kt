package io.github.shiroel.aries.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentViewportTest {
    // D119: a portrait phone's remaining pane stays regular with a keyboard;
    // only crossing the height threshold enables compact chrome.
    @Test fun compactDependsOnRemainingHeight() {
        assertFalse(agentViewportIsCompact(820.dp))
        assertFalse(agentViewportIsCompact(500.dp)) // portrait after IME insets
        assertFalse(agentViewportIsCompact(480.dp))
        assertTrue(agentViewportIsCompact(479.dp))
        assertTrue(agentViewportIsCompact(220.dp)) // landscape/split screen with IME
    }
}
