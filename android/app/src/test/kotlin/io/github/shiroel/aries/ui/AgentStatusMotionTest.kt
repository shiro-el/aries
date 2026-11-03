package io.github.shiroel.aries.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.shiroel.aries.core.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentStatusMotionTest {
    private val motion = object : MotionDurationScale {
        var scale by mutableFloatStateOf(1f)
        override val scaleFactor get() = scale
    }
    @get:Rule val compose = createComposeRule(effectContext = motion)

    @Test
    fun onlyTheCursorBlinksAndReducedMotionLeavesItVisible() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { AgentStatusMark(AgentStatus.Working, Modifier.background(Color.White)) } }
        compose.mainClock.advanceTimeBy(100)
        assertTrue("cursor initially visible", cursorPixels() > 0)
        compose.mainClock.advanceTimeBy(600)
        assertEquals("cursor off during second half of blink", 0, cursorPixels())
        compose.runOnUiThread { motion.scale = 0f }
        compose.mainClock.advanceTimeBy(32)
        assertTrue("reduced motion leaves cursor visible", cursorPixels() > 0)
        compose.mainClock.advanceTimeBy(2200)
        assertTrue("reduced motion stays still", cursorPixels() > 0)
    }

    private fun cursorPixels(): Int {
        val pixels = compose.onNodeWithContentDescription("Working").captureToImage().toPixelMap()
        return (pixels.width * 3 / 5 until pixels.width).sumOf { x ->
            (pixels.height / 2 until pixels.height).count { y -> pixels[x, y] != Color.White }
        }
    }
}
