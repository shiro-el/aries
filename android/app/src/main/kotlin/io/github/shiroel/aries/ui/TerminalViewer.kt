package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CellRun
import io.github.shiroel.aries.core.CursorPos
import io.github.shiroel.aries.core.KeyMods
import io.github.shiroel.aries.core.TerminalKey
import kotlinx.coroutines.channels.Channel
import io.github.shiroel.aries.core.ScreenFrame
import io.github.shiroel.aries.core.ScreenLine
import io.github.shiroel.aries.core.TerminalExit
import io.github.shiroel.aries.core.TerminalInfo

/**
 * One open Terminal's screen as the core's frames built it. The grid is
 * the Terminal's own size (observe mode): the desktop decides it.
 */
class TerminalViewer(val terminal: TerminalInfo) {
    var cols by mutableIntStateOf(0)
        private set
    var rows by mutableIntStateOf(0)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var cursor by mutableStateOf<CursorPos?>(null)
        private set

    /** How the program ended, once it did. */
    var exit by mutableStateOf<TerminalExit?>(null)
        private set

    /** The stream ended without an exit (connection lost). */
    var closed by mutableStateOf(false)
        private set

    /** Bumped on every frame; the canvas redraws when it changes. */
    var version by mutableIntStateOf(0)
        private set

    private var lines: Array<ScreenLine?> = emptyArray()

    fun apply(frame: ScreenFrame) {
        val c = frame.cols.toInt()
        val r = frame.rows.toInt()
        if (frame.full || c != cols || r != rows) {
            cols = c
            rows = r
            lines = arrayOfNulls(r)
        }
        for (line in frame.lines) {
            val i = line.row.toInt()
            if (i < lines.size) lines[i] = line
        }
        cursor = frame.cursor
        title = frame.title
        version++
    }

    fun exited(e: TerminalExit) {
        exit = e
    }

    fun markClosed() {
        closed = true
    }

    fun line(row: Int): ScreenLine? = lines.getOrNull(row)

    /**
     * Typing mode: attached as an ordinary viewer whose grid is the phone's
     * ([phoneGrid]). Starts on input focus ([startTyping]) or right away
     * when the default attach mode is input.
     */
    var typing by mutableStateOf(false)
        private set

    /** Columns and rows the screen has room for while [typing]; the model attaches and resizes to it. */
    var phoneGrid by mutableStateOf<Pair<Int, Int>?>(null)
        internal set

    /** Text the keyboard is still composing (not sent yet). */
    var composing by mutableStateOf("")
        internal set

    /** Ctrl from the key bar, held for the next key only. */
    var ctrl by mutableStateOf(false)
        private set

    /** What was typed, in order, until the model sends it. */
    internal val inputs = Channel<TerminalInput>(Channel.UNLIMITED)

    fun startTyping() {
        typing = true
    }

    fun toggleCtrl() {
        ctrl = !ctrl
    }

    /** Types [text]; a newline is Enter. A held Ctrl applies to the first character. */
    fun type(text: String) {
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest[0] == '\n') {
                press(TerminalKey.Enter)
                rest = rest.substring(1)
                continue
            }
            if (ctrl) {
                val n = Character.charCount(rest.codePointAt(0))
                press(TerminalKey.Char(rest.substring(0, n)))
                rest = rest.substring(n)
                continue
            }
            val end = rest.indexOf('\n').let { if (it < 0) rest.length else it }
            inputs.trySend(TerminalInput.Text(rest.substring(0, end)))
            rest = rest.substring(end)
        }
    }

    /** Presses [key] with a held Ctrl (then lets go of it) and any extra [mods]. */
    fun press(key: TerminalKey, mods: KeyMods = KeyMods(ctrl = false, alt = false, shift = false)) {
        inputs.trySend(TerminalInput.Key(key, mods.copy(ctrl = mods.ctrl || ctrl)))
        ctrl = false
    }

    /** The visible text, one string per row (for accessibility and tests). */
    fun text(): String = (0 until rows).joinToString("\n") { row ->
        val sb = StringBuilder()
        line(row)?.runs?.forEach { run ->
            while (sb.length < run.column.toInt()) sb.append(' ')
            sb.append(run.text)
        }
        sb.toString().trimEnd()
    }
}

/** One thing typed into a Terminal. */
sealed interface TerminalInput {
    data class Text(val text: String) : TerminalInput
    data class Key(val key: TerminalKey, val mods: KeyMods) : TerminalInput
}

private val TerminalBackground = Color(0xFF101214)
private val TerminalForeground = Color(0xFFD8DADC)

@Composable
fun TerminalScreen(viewer: TerminalViewer, onBack: () -> Unit) {
    // The keyboard pushes the input and key bar up and shrinks the screen
    // (the Scaffold already keeps clear of the navigation bar).
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
            Column(Modifier.weight(1f)) {
                Text(viewer.title?.ifBlank { null } ?: displayName(viewer.terminal), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        if (viewer.typing) R.string.terminal_typing else R.string.terminal_observing,
                        viewer.terminal.address,
                        viewer.cols,
                        viewer.rows,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        viewer.exit?.let { Banner(exitText(it)) }
        if (viewer.closed && viewer.exit == null) Banner(stringResource(R.string.terminal_connection_closed))
        TerminalCanvas(viewer, Modifier.fillMaxWidth().weight(1f))
        if (viewer.typing) KeyBar(viewer)
        TerminalInput(viewer)
    }
}

/** The input field under the screen: tap it to type (and to get the keyboard). */
@Composable
private fun TerminalInput(viewer: TerminalViewer) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.CenterStart,
    ) {
        AndroidView(
            factory = { TerminalInputView(it, viewer) },
            update = { it.viewer = viewer },
            modifier = Modifier.matchParentSize(),
        )
        val composing = viewer.composing
        Text(
            when {
                composing.isNotEmpty() -> composing
                viewer.typing -> stringResource(R.string.terminal_input_typing)
                else -> stringResource(R.string.terminal_input_hint)
            },
            Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Keys a phone keyboard lacks: Esc, Tab, Ctrl (for the next key), arrows, and Ctrl-C. */
@Composable
private fun KeyBar(viewer: TerminalViewer) {
    // Every key fits on one row, so none is hidden off screen.
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        BarKey(R.string.key_esc) { viewer.press(TerminalKey.Escape) }
        BarKey(R.string.key_tab) { viewer.press(TerminalKey.Tab) }
        BarKey(R.string.key_ctrl, held = viewer.ctrl, onClick = viewer::toggleCtrl)
        BarKey(R.string.key_up) { viewer.press(TerminalKey.Up) }
        BarKey(R.string.key_down) { viewer.press(TerminalKey.Down) }
        BarKey(R.string.key_left) { viewer.press(TerminalKey.Left) }
        BarKey(R.string.key_right) { viewer.press(TerminalKey.Right) }
        BarKey(R.string.key_interrupt) {
            viewer.press(TerminalKey.Char("c"), KeyMods(ctrl = true, alt = false, shift = false))
        }
    }
}

@Composable
private fun RowScope.BarKey(label: Int, held: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.weight(1f).height(40.dp).padding(2.dp)
            .background(if (held) colors.primary else colors.surface)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(label),
            color = if (held) colors.onPrimary else colors.onSurface,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun exitText(e: TerminalExit): String {
    val code = e.exitCode
    return when {
        e.reason == "exited" && code != null -> stringResource(R.string.terminal_exited_code, code)
        e.signal != null -> stringResource(R.string.terminal_exited_signal, e.signal!!)
        else -> stringResource(R.string.terminal_exited_reason, e.reason)
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Base text size; smaller when the Terminal is too wide for the screen. */
private val MaxFont = 13.sp

@Composable
private fun TerminalCanvas(viewer: TerminalViewer, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Advance width and line height of one monospace cell per font px.
    val probe = remember(measurer) {
        measurer.measure("M", TextStyle(fontFamily = FontFamily.Monospace, fontSize = 100.sp))
    }
    val description = viewer.version.let { viewer.text() }
    BoxWithConstraints(modifier.background(TerminalBackground)) {
        val cols = viewer.cols.coerceAtLeast(1)
        val widthPx = with(density) { maxWidth.toPx() }
        val per100 = probe.size.width.toFloat().coerceAtLeast(1f)
        val maxPx = with(density) { MaxFont.toPx() }
        // Typing: the phone sets the grid, at the base text size. Watching:
        // the desktop's grid, shrunk to fit the width.
        val fontPx = if (viewer.typing) maxPx else minOf(maxPx, widthPx / cols / per100 * 100f)
        val cellW = per100 * fontPx / 100f
        val cellH = (probe.size.height * fontPx / 100f).coerceAtLeast(1f)
        if (viewer.typing) {
            val heightPx = with(density) { maxHeight.toPx() }
            val grid = (widthPx / cellW).toInt().coerceAtLeast(1) to (heightPx / cellH).toInt().coerceAtLeast(1)
            SideEffect { if (viewer.phoneGrid != grid) viewer.phoneGrid = grid }
        }
        val fontSize = with(density) { fontPx.toSp() }
        val height: Dp = with(density) { (cellH * viewer.rows).toDp() }
        Canvas(
            Modifier.fillMaxWidth().height(height).testTag("terminal-screen")
                .semantics { contentDescription = description },
        ) {
            viewer.version // redraw on every frame
            val base = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, color = TerminalForeground)
            for (row in 0 until viewer.rows) {
                val y = row * cellH
                for (run in viewer.line(row)?.runs.orEmpty()) {
                    val x = run.column.toInt() * cellW
                    var fg = run.fg?.let(::rgb) ?: TerminalForeground
                    var bg = run.bg?.let(::rgb)
                    if (run.inverse) {
                        val f = fg
                        fg = bg ?: TerminalBackground
                        bg = f
                    }
                    if (run.dim) fg = fg.copy(alpha = 0.6f)
                    bg?.let { drawRect(it, Offset(x, y), Size(run.width.toInt() * cellW, cellH)) }
                    val style = styleOf(base, run, fg)
                    if (run.wide) {
                        // Each wide character gets exactly two cells.
                        var cx = x
                        run.text.codePoints().forEach { cp ->
                            drawText(measurer, String(Character.toChars(cp)), Offset(cx, y), style)
                            cx += 2 * cellW
                        }
                    } else if (run.text.isNotBlank()) {
                        drawText(measurer, run.text, Offset(x, y), style)
                    }
                }
            }
            viewer.cursor?.let { c ->
                drawRect(
                    TerminalForeground.copy(alpha = 0.5f),
                    Offset(c.column.toInt() * cellW, c.row.toInt() * cellH),
                    Size(cellW, cellH),
                )
            }
        }
    }
}

private fun styleOf(base: TextStyle, run: CellRun, fg: Color): TextStyle {
    val decorations = buildList {
        if (run.underline) add(TextDecoration.Underline)
        if (run.strikeout) add(TextDecoration.LineThrough)
    }
    return base.copy(
        color = fg,
        fontWeight = if (run.bold) FontWeight.Bold else null,
        fontStyle = if (run.italic) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}

private fun rgb(v: UInt): Color = Color(0xFF000000.toInt() or v.toInt())
