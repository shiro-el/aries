package io.github.shiroel.aries

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.SavedLaunch

/** Only identifiers cross the launcher boundary. Commands are resolved after connecting. */
data class SavedLaunchTarget(val hostId: String, val launchId: String) {
    val id: String get() = Uri.Builder().scheme("aries-launch").authority(hostId).appendPath(launchId).build().toString()

    fun intent(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(ACTION).setData(Uri.parse(id))
        .putExtra(HOST, hostId).putExtra(LAUNCH, launchId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    companion object {
        const val ACTION = "io.github.shiroel.aries.RUN_SAVED_LAUNCH"
        private const val HOST = "host_id"
        private const val LAUNCH = "saved_launch_id"

        fun from(intent: Intent?): SavedLaunchTarget? {
            if (intent?.action != ACTION || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val host = intent.getStringExtra(HOST)?.takeIf { it.isNotBlank() } ?: return null
            val launch = intent.getStringExtra(LAUNCH)?.takeIf { it.isNotBlank() } ?: return null
            return SavedLaunchTarget(host, launch)
        }
    }
}

object LaunchShortcuts {
    private fun shortcut(context: Context, hostId: String, saved: SavedLaunch, hostName: String? = null, rank: Int = 0): ShortcutInfo {
        val target = SavedLaunchTarget(hostId, saved.id)
        return ShortcutInfo.Builder(context, target.id)
            .setShortLabel(saved.name)
            .setLongLabel(hostName?.let { context.getString(R.string.shortcuts_host_label, saved.name, it) } ?: saved.name)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(SavedLaunchAuthorization.intent(context, target)).setRank(rank).build()
    }

    private fun same(a: ShortcutInfo, b: ShortcutInfo) = a.id == b.id && a.shortLabel == b.shortLabel &&
        a.longLabel == b.longLabel && a.rank == b.rank && a.intent?.let { old ->
            b.intent?.let { new -> old.filterEquals(new) && SavedLaunchAuthorization.sameCredential(old, new) }
        } == true

    /** Call on IO: Android may take seconds to publish. No SSH connection or key opening. */
    @Synchronized
    fun sync(context: Context, core: AriesCoreInterface) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val all = core.hosts().flatMap { host -> core.savedLaunches(host.id).map { host to it } }
            .mapIndexed { index, (host, saved) -> shortcut(context, host.id, saved, host.name, index) }
        val valid = all.associateBy { it.id }
        val pinned = manager.pinnedShortcuts
        val removed = pinned.filter { it.isEnabled && it.id !in valid }.map { it.id }
        if (removed.isNotEmpty()) manager.disableShortcuts(removed, context.getString(R.string.shortcuts_missing_body))
        val restored = pinned.filter { !it.isEnabled && it.id in valid }.map { it.id }
        if (restored.isNotEmpty()) manager.enableShortcuts(restored)
        val changedPins = pinned.mapNotNull { pin -> valid[pin.id]?.takeUnless { same(pin, it) } }
        if (changedPins.isNotEmpty()) manager.updateShortcuts(changedPins)
        val dynamic = all.take(minOf(4, manager.maxShortcutCountPerActivity))
        val current = manager.dynamicShortcuts.sortedBy { it.rank }
        // Re-entering the app must not consume Android's shortcut update quota.
        if (current.size != dynamic.size || current.zip(dynamic).any { !same(it.first, it.second) }) {
            manager.setDynamicShortcuts(dynamic)
        }
    }

    fun canPin(context: Context): Boolean = context.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    fun pin(context: Context, hostId: String, saved: SavedLaunch): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        return manager.isRequestPinShortcutSupported && manager.requestPinShortcut(shortcut(context, hostId, saved), null)
    }
}
