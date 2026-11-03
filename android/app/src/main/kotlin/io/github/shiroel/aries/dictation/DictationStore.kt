package io.github.shiroel.aries.dictation

import android.content.Context
import android.util.AtomicFile
import io.github.shiroel.aries.KeystoreVault
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.KeyVault
import java.io.File

enum class DictationModel(val apiName: String) {
    LARGE("whisper-large-v3"), TURBO("whisper-large-v3-turbo")
}

data class DictationOptions(
    val language: String? = null,
    val model: DictationModel = DictationModel.LARGE,
    val vocabulary: String = "",
    val autoContext: Boolean = false,
)

/** Device-only credentials, deliberately outside the core's state/export directory. */
class DictationStore(context: Context, private val vault: KeyVault = KeystoreVault("aries-groq")) {
    private val appContext = context.applicationContext ?: context
    private val keyFile = AtomicFile(File(appContext.noBackupFilesDir, "groq-key.sealed"))
    private val preferences = appContext.getSharedPreferences("dictation", Context.MODE_PRIVATE)

    val hasKey: Boolean get() = readKey() != null

    fun readKey(): String? = try {
        if (!keyFile.baseFile.exists()) null
        else vault.open(keyFile.readFully(), KeyProtection.PLAIN).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        // Missing/invalidated Keystore keys fail closed. Never log the secret or exception.
        null
    }

    fun saveKey(value: String) {
        require(value.isNotBlank() && !value.contains('\r') && !value.contains('\n'))
        val sealed = vault.seal(value.trim().toByteArray(Charsets.UTF_8), KeyProtection.PLAIN)
        val output = keyFile.startWrite()
        try {
            output.write(sealed)
            keyFile.finishWrite(output)
        } catch (error: Exception) {
            keyFile.failWrite(output)
            throw error
        }
    }

    fun clearKey() = keyFile.delete()

    fun options() = DictationOptions(
        language = preferences.getString("language", null)?.takeIf { it.matches(Regex("[a-z]{2}")) },
        model = DictationModel.entries.find { it.name == preferences.getString("model", null) } ?: DictationModel.LARGE,
        vocabulary = preferences.getString("vocabulary", "").orEmpty(),
        autoContext = preferences.getBoolean("auto-context", false),
    )

    fun saveOptions(options: DictationOptions) {
        require(options.language == null || options.language.matches(Regex("[a-z]{2}")))
        preferences.edit().putString("language", options.language).putString("model", options.model.name)
            .putString("vocabulary", options.vocabulary).putBoolean("auto-context", options.autoContext).apply()
    }
}
