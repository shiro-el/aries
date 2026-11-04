package io.github.shiroel.aries.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf

/** Observe Android's speech-event boundary without replacing Compose's semantics delegate. */
internal fun recordAccessibilityAnnouncements(compose: ComposeContentTestRule): MutableList<String> {
    val messages = mutableListOf<String>()
    compose.runOnIdle {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        WindowInspector.getGlobalWindowViews().forEach { root ->
            root.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                    if (event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) messages += event.text.joinToString(". ")
                    return super.onRequestSendAccessibilityEvent(host, child, event)
                }
            }
        }
    }
    return messages
}
