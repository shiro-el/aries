package io.github.shiroel.aries

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A reusable capability for one saved launch, issued only when publishing a shortcut.
 * The private installation key survives upgrades, but never backup/restore. A launcher
 * holding a genuine capability can invoke that launch; IDs and referrers alone cannot.
 */
internal object SavedLaunchAuthorization {
    private const val TOKEN = "saved_launch_auth_v1"
    private const val ALGORITHM = "HmacSHA256"

    fun intent(context: Context, target: SavedLaunchTarget): Intent = target.intent(context).also { intent ->
        credential(context, target, create = true)?.let { intent.putExtra(TOKEN, it) }
    }

    fun trusted(context: Context, intent: Intent): Boolean {
        val target = SavedLaunchTarget.from(intent) ?: return false
        val supplied = intent.getStringExtra(TOKEN) ?: return false
        if (supplied.length != 44) return false
        val expected = credential(context, target, create = false) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
    }

    fun sameCredential(a: Intent, b: Intent) = a.getStringExtra(TOKEN) == b.getStringExtra(TOKEN)

    private fun credential(context: Context, target: SavedLaunchTarget, create: Boolean): String? = try {
        key(context, create)?.let { key ->
            val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
            Base64.encodeToString(mac.doFinal(target.id.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
    } catch (_: IOException) {
        null // Storage failure must require approval, never authorize a launch.
    } catch (_: GeneralSecurityException) {
        null
    }

    // Keep this lock separate from slow ShortcutManager publication, which runs on IO.
    @Synchronized
    private fun key(context: Context, create: Boolean): ByteArray? {
        val file = AtomicFile(File(context.noBackupFilesDir, "saved-launch-key-v1"))
        val stored = try { file.readFully() } catch (_: FileNotFoundException) { null }
        if (stored?.size == 32) return stored
        if (!create) return null
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val output = file.startWrite()
        try {
            output.write(fresh)
            file.finishWrite(output)
        } catch (e: IOException) {
            file.failWrite(output)
            throw e
        }
        return fresh
    }
}
