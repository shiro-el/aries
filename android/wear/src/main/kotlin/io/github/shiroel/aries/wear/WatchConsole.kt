package io.github.shiroel.aries.wear

import android.content.Context

/** Console tokens shared by the Tile and Compose watch screens. */
internal object WatchConsole {
    const val CORNER_RADIUS_DP = 2f
    const val NIGHT = 0xff121315.toInt()
    const val SAFFRON = 0xfff4b73a.toInt()
    const val TEXT = 0xffece8e3.toInt()
    const val MUTED = 0xffa9a49e.toInt()
    const val ERROR = 0xfff2756a.toInt()
    const val CARD = 0xff1d1e20.toInt()

    fun code(context: Context, status: String, requestKind: String = "") = context.getString(when (status.lowercase()) {
        "blocked" -> if (requestKind.equals("question", true)) R.string.wear_code_question else R.string.wear_code_blocked
        "error" -> R.string.wear_code_error
        "working" -> R.string.wear_code_working
        "done" -> R.string.wear_code_done
        else -> R.string.wear_code_idle
    })

    fun status(context: Context, status: String) = context.getString(when (status.lowercase()) {
        "blocked" -> R.string.wear_status_blocked
        "error" -> R.string.wear_status_error
        "working" -> R.string.wear_status_working
        "done" -> R.string.wear_status_done
        else -> R.string.wear_status_idle
    })

    fun color(status: String): Int = when (status.lowercase()) {
        "blocked", "working" -> SAFFRON
        "error" -> ERROR
        else -> MUTED
    }

    fun icon(status: String): Int = when (status.lowercase()) {
        "blocked" -> R.drawable.wear_status_blocked
        "error" -> R.drawable.wear_status_error
        "working" -> R.drawable.wear_status_working
        "done" -> R.drawable.wear_status_done
        else -> R.drawable.wear_status_idle
    }
}
