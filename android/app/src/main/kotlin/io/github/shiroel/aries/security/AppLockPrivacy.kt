package io.github.shiroel.aries.security

import android.view.Window
import android.view.WindowManager

/** Applied before the first frame and when the preference changes. */
fun AppLock.applyPrivacy(window: Window) {
    window.setAppLockPrivacy(enabled && hidePreviews)
}

internal fun Window.setAppLockPrivacy(protect: Boolean) {
    if (protect) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
