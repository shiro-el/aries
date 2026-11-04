package io.github.shiroel.aries

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.work.workDataOf
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class)
class PlaintextCopiesTest {
    @Test
    fun upgradeErasesLegacyReplyWorkIncludingSqlitePagesAndPreservesOtherWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.noBackupFilesDir, "androidx.work.workdb")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE WorkSpec (id TEXT PRIMARY KEY, worker_class_name TEXT, input BLOB)")
            for ((index, worker) in listOf("InlineActionWorker", "CallReplyWorker", "QuestionReplyWorker", "SnapshotWorker").withIndex()) {
                db.execSQL("INSERT INTO WorkSpec VALUES (?, ?, ?)", arrayOf("$index",
                    "io.github.shiroel.aries.$worker", workDataOf("text" to "legacy-reply-secret-180").toByteArray()))
            }
        }
        // The unrelated job carries no reply payload in real use.
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE WorkSpec SET input = ? WHERE id = '3'", arrayOf(workDataOf("host" to "h1").toByteArray()))
        }
        PlaintextCopies.upgrade(context)
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.rawQuery("SELECT id FROM WorkSpec", null).use { rows ->
                assertEquals(1, rows.count)
                assertTrue(rows.moveToFirst())
                assertEquals("3", rows.getString(0))
            }
            // The one-time migration must not remove newly scheduled encrypted work.
            it.execSQL("INSERT INTO WorkSpec VALUES ('new', 'io.github.shiroel.aries.InlineActionWorker', ?)",
                arrayOf(workDataOf("text" to "sealed-v1:encrypted").toByteArray()))
        }
        PlaintextCopies.upgrade(context)
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT id FROM WorkSpec", null).use { rows -> assertEquals(2, rows.count) }
        }
        for (file in path.parentFile!!.listFiles().orEmpty().filter { it.name.startsWith(path.name) }) {
            assertFalse("plaintext survives in ${file.name}", file.readBytes().toString(Charsets.ISO_8859_1).contains("legacy-reply-secret-180"))
        }
    }
}
