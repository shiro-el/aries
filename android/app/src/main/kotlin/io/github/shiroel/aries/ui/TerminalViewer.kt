package io.github.shiroel.aries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.translate
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentView
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
class TerminalViewer(terminal: TerminalInfo) {
    var terminal by mutableStateOf(terminal)
        private set

    /** Reuse the Host's existing Terminal watch to keep header metadata current. */
    fun update(terminals: List<TerminalInfo>) {
        terminals.find { it.address == terminal.address }?.let { terminal = it }
    }

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

    /** The Host line this screen streams over; its state shows while it reconnects. */
    var link: HostLink? = null

    /** Bumped on every frame; the canvas redraws when it changes. */
    var version by mutableIntStateOf(0)
        private set

    private var lines: Array<ScreenLine?> = emptyArray()

    /** Lines the view is scrolled back into the scrollback; 0 = live. */
    var scrolled by mutableIntStateOf(0)
        private set

    /** Scrollback lines above the live screen. */
    var history by mutableIntStateOf(0)
        private set

    /** Moves the core's view (`TerminalView.scrollTo`); set while attached. */
    var scroller: ((UInt) -> UInt)? = null

    /** Reads [lines] further back (negative: towards live), within the scrollback. */
    fun scrollBack(lines: Int) {
        val to = (scrolled + lines).coerceIn(0, maxOf(history, scrolled))
        if (to != scrolled) scrollTo(to)
    }

    fun scrollToLive() = scrollTo(0)

    private fun scrollTo(to: Int) {
        val at = scroller?.invoke(to.toUInt()) ?: return
        scrolled = at.toInt()
    }

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
        scrolled = frame.scrolled.toInt()
        history = frame.history.toInt()
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

    /**
     * The part of the keyboard's composing text not sent yet: the Hangul
     * syllable still being built (shown at the cursor and in the input bar).
     */
    var composing by mutableStateOf("")
        private set

    /** The rest of the composing text, already typed into the Terminal. */
    private var echoed = ""

    /**
     * Called when typing outside the keyboard (the key bar, a key event) ends
     * a composition the keyboard may still think is open, so it can reset.
     */
    var onCompositionInterrupted: (() -> Unit)? = null

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

    /**
     * The keyboard's composing text is now [text]. All of it but a trailing
     * Hangul syllable goes to the Terminal right away, so keyboards that
     * compose whole Latin words still type key by key; the syllable is held
     * until the keyboard moves on, as it can still change (한 + ㅏ -> 하나).
     */
    fun compose(text: String) {
        val last = if (text.isEmpty()) 0 else text.codePointBefore(text.length)
        // A syllable already sent and unchanged (하나 -> 하) stays sent.
        val held = if (isHangul(last) && !echoed.startsWith(text)) Character.charCount(last) else 0
        retype(text.substring(0, text.length - held))
        composing = text.substring(text.length - held)
    }

    /** The keyboard committed [text] in place of what it was composing. */
    fun commit(text: String) {
        retype(text)
        echoed = ""
        composing = ""
    }

    /** The keyboard finished composing: what it composed stays as is. */
    fun finishComposing() = commit(echoed + composing)

    /** Deletes the last [count] characters being composed; false when nothing is. */
    fun deleteComposing(count: Int): Boolean {
        val text = echoed + composing
        if (text.isEmpty()) return false
        var end = text.length
        repeat(count) { if (end > 0) end = text.offsetByCodePoints(end, -1) }
        compose(text.substring(0, end))
        return true
    }

    /** Makes the composed text already in the Terminal [target]: Backspace over what differs, type the rest. */
    private fun retype(target: String) {
        var same = 0
        while (same < echoed.length && same < target.length && echoed.codePointAt(same) == target.codePointAt(same)) {
            same += Character.charCount(echoed.codePointAt(same))
        }
        repeat(echoed.codePointCount(same, echoed.length)) { pressNow(TerminalKey.Backspace, NoMods) }
        typeNow(target.substring(same))
        echoed = target
    }

    /** Typing that is not the keyboard's composing ends the composition first, keeping its text. */
    private fun interruptComposition() {
        if (echoed.isEmpty() && composing.isEmpty()) return
        finishComposing()
        onCompositionInterrupted?.invoke()
    }

    /** Types [text]; a newline is Enter. A held Ctrl applies to the first character. */
    fun type(text: String) {
        interruptComposition()
        typeNow(text)
    }

    private fun typeNow(text: String) {
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest[0] == '\n') {
                pressNow(TerminalKey.Enter, NoMods)
                rest = rest.substring(1)
                continue
            }
            if (ctrl) {
                val n = Character.charCount(rest.codePointAt(0))
                pressNow(TerminalKey.Char(rest.substring(0, n)), NoMods)
                rest = rest.substring(n)
                continue
            }
            val end = rest.indexOf('\n').let { if (it < 0) rest.length else it }
            send(TerminalInput.Text(rest.substring(0, end)))
            rest = rest.substring(end)
        }
    }

    /** Presses [key] with a held Ctrl (then lets go of it) and any extra [mods]. */
    fun press(key: TerminalKey, mods: KeyMods = NoMods) {
        interruptComposition()
        pressNow(key, mods)
    }

    private fun pressNow(key: TerminalKey, mods: KeyMods) {
        send(TerminalInput.Key(key, mods.copy(ctrl = mods.ctrl || ctrl)))
        ctrl = false
    }

    /** Typing while scrolled back returns to the live screen, as terminals do. */
    private fun send(input: TerminalInput) {
        if (scrolled != 0) scrollToLive()
        inputs.trySend(input)
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

private val NoMods = KeyMods(ctrl = false, alt = false, shift = false)

/** Hangul syllables and jamo: what a Korean keyboard builds up one syllable at a time. */
private fun isHangul(cp: Int): Boolean =
    cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F ||
        cp in 0xA960..0xA97F || cp in 0xD7B0..0xD7FF

/** One thing typed into a Terminal. */
sealed interface TerminalInput {
    data class Text(val text: String) : TerminalInput
    data class Key(val key: TerminalKey, val mods: KeyMods) : TerminalInput
}

private val TerminalBackground = Color(0xFF101214)
private val TerminalForeground = Color(0xFFD8DADC)
private val SelectionColor = Color(0x664D8DFF)

@Composable
fun TerminalScreen(viewer: TerminalViewer, onBack: () -> Unit, onView: ((AgentView) -> Unit)? = null) {
    // The keyboard pushes the input and key bar up and shrinks the screen
    // (the Scaffold already keeps clear of the navigation bar).
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
        AgentScreenHeader(viewer.terminal, viewer.link?.host?.name, onBack, AgentView.TERMINAL, onView,
            title = viewer.terminal.name?.takeIf { it.isNotBlank() } ?: viewer.title?.takeIf { it.isNotBlank() } ?: displayName(viewer.terminal))
        Text(
            stringResource(if (viewer.typing) R.string.terminal_typing else R.string.terminal_observing,
                viewer.terminal.address, viewer.cols, viewer.rows),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        viewer.exit?.let { Banner(exitText(it)) }
        val line = viewer.link?.state
        if (viewer.exit == null && line == LinkState.Reconnecting) Banner(stringResource(R.string.reconnecting))
        if (viewer.exit == null && (viewer.closed || line is LinkState.Down)) {
            Banner(stringResource(R.string.terminal_connection_closed))
        }
        val look = remember(viewer) { TerminalLook(viewer) }
        TerminalLookBar(viewer, look)
        TerminalCanvas(viewer, look, Modifier.fillMaxWidth().weight(1f))
        if (viewer.typing) KeyBar(viewer)
        TerminalInput(viewer)
    }
}

/** The input field under the screen: tap it to type (and to get the keyboard). */
@Composable
private fun TerminalInput(viewer: TerminalViewer) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
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
            .border(1.dp, colors.outlineVariant)
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
private val MaxFont = 14.sp

@Composable
private fun TerminalCanvas(viewer: TerminalViewer, look: TerminalLook, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Advance width and line height of one monospace cell at 100sp (in px).
    val probe = remember(measurer) {
        measurer.measure("M", TextStyle(fontFamily = FontFamily.Monospace, fontSize = 100.sp))
    }
    val description = viewer.version.let { viewer.text() }
    // The selected cells moved away.
    LaunchedEffect(look, viewer.scrolled, viewer.cols, viewer.rows) { look.selection = null }
    BoxWithConstraints(modifier.background(TerminalBackground)) {
        val cols = viewer.cols.coerceAtLeast(1)
        val widthPx = with(density) { maxWidth.toPx() }
        val per100 = probe.size.width.toFloat().coerceAtLeast(1f)
        // The probe's font size in px: cell sizes scale with font px over it.
        val probePx = with(density) { 100.sp.toPx() }
        val maxPx = with(density) { MaxFont.toPx() }
        // Typing: the phone sets the grid, at the base text size. Watching:
        // the desktop's grid, shrunk to fit the width.
        val baseFontPx = if (viewer.typing) maxPx else minOf(maxPx, widthPx / cols / per100 * probePx)
        val baseCellW = per100 * baseFontPx / probePx
        val baseCellH = (probe.size.height * baseFontPx / probePx).coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }
        if (viewer.typing) {
            val grid = (widthPx / baseCellW).toInt().coerceAtLeast(1) to (heightPx / baseCellH).toInt().coerceAtLeast(1)
            SideEffect { if (viewer.phoneGrid != grid) viewer.phoneGrid = grid }
        }
        look.layout(baseCellW, baseCellH, widthPx, heightPx)
        // Zoom and pan: the phone's view only, the grid stays the Terminal's.
        val fontPx = baseFontPx * look.zoom
        val cellW = look.cellW
        val cellH = look.cellH
        val fontSize = with(density) { fontPx.toSp() }
        Canvas(
            Modifier.fillMaxSize().clipToBounds().testTag("terminal-screen")
                .semantics { contentDescription = description }
                .terminalGestures(look, viewer),
        ) {
            viewer.version // redraw on every frame
            val base = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, color = TerminalForeground)
            translate(look.panX, look.panY) {
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
                // The syllable still being composed, where it will land (two cells, as Hangul is wide).
                if (viewer.composing.isNotEmpty()) {
                    val at = Offset(c.column.toInt() * cellW, c.row.toInt() * cellH)
                    drawRect(TerminalForeground, at, Size(2 * cellW, cellH))
                    drawText(measurer, viewer.composing, at, base.copy(color = TerminalBackground, textDecoration = TextDecoration.Underline))
                }
            }
            look.selection?.ordered()?.let { s ->
                for (row in s.start.row..s.end.row) {
                    val from = if (row == s.start.row) s.start.col else 0
                    val to = if (row == s.end.row) s.end.col else viewer.cols - 1
                    drawRect(
                        SelectionColor,
                        Offset(from * cellW, row * cellH),
                        Size((to - from + 1) * cellW, cellH),
                    )
                }
            }
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
