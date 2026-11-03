package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.TerminalInfo

/** Live facts take precedence; the launch fields remain useful without them. */
val TerminalInfo.currentCwd: String?
    get() = foreground?.cwd?.takeIf { it.isNotBlank() } ?: cwd

val TerminalInfo.currentProgram: String?
    get() = foreground?.name?.takeIf { it.isNotBlank() }
        ?: (foreground?.argv?.firstOrNull() ?: argv.firstOrNull())
            ?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
