package io.github.shiroel.aries.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Renderer color seam; examples and threshold from herdr-app's vt.rs tests. */
class TerminalContrastTest {
    @Test
    fun lowContrastTextUsesHerdrReplacementColorsAndReadableTextKeepsItsColor() {
        val ink = Color(0xFF25272C)
        val white = Color.White
        val red = Color(0xFF461E20)
        assertEquals(ink, readableTerminalForeground(ink, white, "X", true))
        assertEquals(Color(0xFFF2F2F4), readableTerminalForeground(ink, red, "X", true))
        assertEquals(Color(0xFF1B1B1E), readableTerminalForeground(Color(0xFFFAFAFA), white, "X", true))
        val dim = Color(0xFF7B7E87)
        assertEquals(dim, readableTerminalForeground(dim, white, "X", true))
        assertEquals(ink, readableTerminalForeground(ink, red, "X", false))
    }

    @Test
    fun theToggleUsesTheDesktopThresholdOfOnePointSix() {
        val bg = Color(0xFF303030)
        val justAbove = Color(0xFF505050) // contrast 1.64
        assertEquals(justAbove, readableTerminalForeground(justAbove, bg, "X", true))
        assertEquals(Color(0xFFF2F2F4), readableTerminalForeground(Color(0xFF4C4C4C), bg, "X", true)) // contrast 1.54
    }

    @Test
    fun desktopGraphicsAndSpritesPreserveIntentionalLowContrast() {
        val fg = Color(0xFF1E1F28)
        val bg = Color(0xFF20212A)
        val graphics = (0x2500..0x259F) + (0xE0B0..0xE0D7) + (0x1FB00..0x1FBFF) +
            (0x1CC00..0x1CEBF) + (0x2800..0x28FF) + listOf(0x23FA)
        for (cp in graphics) {
            assertEquals(cp.toString(16), fg, readableTerminalForeground(fg, bg, String(Character.toChars(cp)), true))
        }
        for (cp in listOf(0x24FF, 0x25A0, 0xE0AF, 0xE0D8, 0x1FAFF, 0x1FC00, 0x1CBFF, 0x1CEC0)) {
            assertEquals(cp.toString(16), Color(0xFFF2F2F4), readableTerminalForeground(fg, bg, String(Character.toChars(cp)), true))
        }
    }
}
