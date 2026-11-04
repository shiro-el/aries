package io.github.shiroel.aries

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore

/** Erase credentials left by installations that supported the removed voice feature. */
internal object RemovedDictation {
    fun upgrade(context: Context) {
        // Cleanup must never prevent startup or log credentials/exceptions.
        runCatching {
            val upgrades = context.getSharedPreferences("removed-features", Context.MODE_PRIVATE)
            if (upgrades.getBoolean("dictation-v1", false)) return

            val filesRemoved = runCatching {
                val file = File(context.noBackupFilesDir, "groq-key.sealed")
                AtomicFile(file).delete()
                listOf("", ".bak", ".new").none { File(file.path + it).exists() }
            }.getOrDefault(false)
            val preferencesRemoved = runCatching {
                // Clear already loaded preferences too, before deleting their backing files.
                context.getSharedPreferences("dictation", Context.MODE_PRIVATE).edit().clear().commit() &&
                    context.deleteSharedPreferences("dictation")
            }.getOrDefault(false)
            val aliasRemoved = runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("aries-groq")
                true
            }.getOrDefault(false)

            // Retry transient failures on a later launch; successful cleanup runs only once.
            if (filesRemoved && preferencesRemoved && aliasRemoved) {
                upgrades.edit().putBoolean("dictation-v1", true).commit()
            }
        }
    }
}
