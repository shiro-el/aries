package io.github.shiroel.aries

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.ui.HostsModel
import io.github.shiroel.aries.ui.LastLocationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Owns the live screen sessions across Activity recreation; never retains an Activity. */
class HostsViewModel(application: Application) : AndroidViewModel(application) {
    private val core = (application as HasCore).core
    val push = AndroidPushPlatform(application)
    val model = HostsModel(
        core, viewModelScope, keyWork = Dispatchers.IO, pushPlatform = push,
        locationStore = LastLocationStore.from(application),
        onSavedLaunchesChanged = ::refreshLaunchShortcuts,
    )

    fun refreshLaunchShortcuts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                LaunchShortcuts.sync(getApplication(), core)
            } catch (_: CoreException) {
                // A concurrent Host removal can be retried on the next foreground entry.
            } catch (_: IllegalStateException) {
                // The launcher service is unavailable until the user unlocks the phone.
            }
        }
    }

    override fun onCleared() {
        model.pauseCalls()
        model.back()
        push.unbind()
    }
}
