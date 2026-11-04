package io.github.shiroel.aries

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File

/** Runs before Aries initializes WorkManager (its Startup initializer is disabled).
 * Remove legacy action work, including completed rows; cancelling/pruning through
 * WorkManager alone would retain input blobs and SQLite free pages. Other jobs,
 * including periodic Last-known state refreshes, are preserved.
 */
internal object PlaintextCopies {
    fun upgrade(context: Context) {
        val preferences = context.getSharedPreferences("private-content-upgrade", Context.MODE_PRIVATE)
        if (preferences.getBoolean("sealed-actions-v1", false)) return
        val name = "androidx.work.workdb"
        for (file in listOf(context.getDatabasePath(name), File(context.noBackupFilesDir, name))) {
            if (!file.exists()) continue
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.setForeignKeyConstraintsEnabled(true)
                db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
                // WorkManager's consumer rules keep Worker class names through R8.
                val classes = arrayOf(InlineActionWorker::class.java.name, CallReplyWorker::class.java.name,
                    QuestionReplyWorker::class.java.name)
                val hasWork = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'WorkSpec'", null)
                    .use { it.moveToFirst() }
                if (hasWork) db.delete("WorkSpec", "worker_class_name IN (?, ?, ?)", classes)
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                db.execSQL("VACUUM")
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        for (entry in manager.activeNotifications) {
            val notification = entry.notification
            (notification.actions.orEmpty().toList() + Notification.WearableExtender(notification).actions)
                .forEach { it.actionIntent?.cancel() }
            notification.deleteIntent?.cancel()
        }
        manager.cancelAll()
        // Before API 34 the OS cannot enumerate alarms; an old snooze expires
        // after its existing one-hour window and the receiver rejects its payload.
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(AlarmManager::class.java).cancelAll()
        check(preferences.edit().putBoolean("sealed-actions-v1", true).commit())
    }
}
