package io.github.shiroel.aries

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import io.github.shiroel.aries.core.CoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** System callbacks read local state off the receiver thread, without opening SSH. */
abstract class HomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        (context.applicationContext as? AriesApplication)?.updateHomeWidgetObservation()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as? HasCore)?.core?.let { HomeWidgets.update(context, it) }
            } catch (_: CoreException) {
                // Keep the last rendered value if local state cannot be read.
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        (context.applicationContext as? AriesApplication)?.updateHomeWidgetObservation()
    }

    override fun onDisabled(context: Context) {
        (context.applicationContext as? AriesApplication)?.updateHomeWidgetObservation()
    }
}

class WaitingHomeWidget : HomeWidgetProvider()
class AgentsHomeWidget : HomeWidgetProvider()
