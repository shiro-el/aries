package io.github.shiroel.aries.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.shiroel.aries.core.KeyMods
import io.github.shiroel.aries.core.TerminalKey

/**
 * The Terminal's input field: a plain focusable View so the soft keyboard
 * talks to our own [TerminalInputConnection] instead of a text editor.
 * Tapping it focuses it and shows the keyboard; focusing it starts typing
 * mode on [viewer] (observe -> ordinary viewer).
 */
@SuppressLint("ViewConstructor")
class TerminalInputView(context: Context, var viewer: TerminalViewer) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { showKeyboard() }
        setOnFocusChangeListener { _, focused -> if (focused) this.viewer.startTyping() }
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password text: keyboards send each key right away, with no
        // autocorrect or word composing. Ticket 18 (Korean IME) revisits it.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this, viewer)
    }

    /** Hardware keys (and `adb shell input`) arrive here, not through the IME. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        sendKey(viewer, event) || super.onKeyDown(keyCode, event)
}

/**
 * What the keyboard types, turned into Terminal input on [viewer]: committed
 * text goes out as is, composing text waits in [TerminalViewer.composing]
 * until the keyboard finishes it, deletes become Backspace/Delete, and key
 * events become [TerminalKey]s.
 */
class TerminalInputConnection(view: View, private val viewer: TerminalViewer) : BaseInputConnection(view, true) {
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // The committed text replaces whatever was composing.
        viewer.composing = ""
        viewer.type(text.toString())
        editable?.clear()
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        viewer.composing = text.toString()
        return true
    }

    override fun finishComposingText(): Boolean {
        val text = viewer.composing
        viewer.composing = ""
        if (text.isNotEmpty()) viewer.type(text)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (viewer.composing.isNotEmpty()) {
            viewer.composing = viewer.composing.dropLast(beforeLength)
            return true
        }
        repeat(beforeLength) { viewer.press(TerminalKey.Backspace) }
        repeat(afterLength) { viewer.press(TerminalKey.Delete) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) sendKey(viewer, event)
        return true
    }
}

/** Sends a key-down [event] to [viewer]; false when it is not a key a Terminal takes. */
internal fun sendKey(viewer: TerminalViewer, event: KeyEvent): Boolean {
    val mods = KeyMods(ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed)
    val key = when (event.keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.Enter
        KeyEvent.KEYCODE_DEL -> TerminalKey.Backspace
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.Delete
        KeyEvent.KEYCODE_TAB -> TerminalKey.Tab
        KeyEvent.KEYCODE_ESCAPE -> TerminalKey.Escape
        KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.Right
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.Home
        KeyEvent.KEYCODE_MOVE_END -> TerminalKey.End
        KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PageUp
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PageDown
        KeyEvent.KEYCODE_INSERT -> TerminalKey.Insert
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            TerminalKey.F((event.keyCode - KeyEvent.KEYCODE_F1 + 1).toUByte())
        else -> null
    }
    if (key != null) {
        // Shift only matters to Tab and the cursor/function keys, not to Enter.
        viewer.press(key, mods)
        return true
    }
    // A character: what the key types without Ctrl/Alt (they are sent as mods).
    val ch = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
    if (ch == 0 || ch and KeyCharacterMapCombining != 0) return false
    val text = String(Character.toChars(ch))
    if (mods.ctrl || mods.alt) {
        viewer.press(TerminalKey.Char(text), mods.copy(shift = false))
    } else {
        viewer.type(text)
    }
    return true
}

private const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
