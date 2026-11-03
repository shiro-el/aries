package io.github.shiroel.aries.ui

import androidx.compose.ui.graphics.Color
import io.github.shiroel.aries.core.CellRun
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.pow

/**
 * herdr-app's opt-in 1.6 contrast rule (vt.rs): retain readable program colors,
 * otherwise choose whichever of near-white and near-black contrasts better.
 * Call after inverse, before applying the cell's faint opacity.
 */
internal fun readableTerminalForeground(fg: Color, bg: Color, text: String, enabled: Boolean): Color {
    if (!enabled || preservesTerminalGraphics(text) || contrast(fg, bg) >= 1.6f) return fg
    val light = Color(0xFFF2F2F4)
    val dark = Color(0xFF1B1B1E)
    return if (contrast(light, bg) >= contrast(dark, bg)) light else dark
}

internal data class TerminalTextSpan(val column: Int, val text: String, val foreground: Color)

/**
 * The core coalesces cells with equal ANSI styles, including graphics next to
 * ordinary text. Correct each grapheme, then group equal colors for drawing.
 * Wide graphemes remain separate so each gets exactly two grid cells.
 */
internal fun terminalTextSpans(run: CellRun, fg: Color, bg: Color, enabled: Boolean): List<TerminalTextSpan> {
    val text = run.text
    if (text.isEmpty()) return emptyList()
    if (!enabled && !run.wide) return listOf(TerminalTextSpan(0, text, fg))
    val cells = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    val spans = mutableListOf<TerminalTextSpan>()
    var start = cells.first()
    var end = cells.next()
    var column = 0
    var groupStart = 0
    var groupColumn = 0
    var groupColor: Color? = null
    while (end != BreakIterator.DONE) {
        val color = readableTerminalForeground(fg, bg, text.substring(start, end), enabled)
        val previous = groupColor
        if (previous != null && (previous != color || run.wide)) {
            spans += TerminalTextSpan(groupColumn, text.substring(groupStart, start), previous)
            groupStart = start
            groupColumn = column
        }
        groupColor = color
        column += if (run.wide) 2 else 1
        start = end
        end = cells.next()
    }
    groupColor?.let { spans += TerminalTextSpan(groupColumn, text.substring(groupStart), it) }
    return spans
}

private fun luminance(color: Color): Float {
    fun channel(value: Float) = if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

private fun contrast(a: Color, b: Color): Float {
    val x = luminance(a)
    val y = luminance(b)
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}

private fun preservesTerminalGraphics(text: String): Boolean {
    if (text.isEmpty()) return false
    val cp = text.codePointAt(0)
    return cp in 0x2500..0x259F || cp in 0xE0B0..0xE0D7 || cp in 0x1FB00..0x1FBFF ||
        cp in 0x1CC00..0x1CEBF ||
        (text.codePointCount(0, text.length) == 1 && (cp in 0x2800..0x28FF || cp == 0x23FA))
}
