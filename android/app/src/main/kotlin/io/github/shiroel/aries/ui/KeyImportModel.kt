package io.github.shiroel.aries.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MAX_PRIVATE_KEY_BYTES = 64 * 1024

/** Retained by HostsModel. Secrets live only in memory, never in saved instance state. */
class KeyImportModel(
    private val scope: CoroutineScope,
    private val performImport: suspend (String, String, String, String?) -> Unit,
) {
    var privateKey by mutableStateOf("")
        private set
    var passphrase by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Message?>(null)
        private set
    var completed by mutableStateOf(false)
        private set

    fun editPrivateKey(value: String) {
        if (value.toByteArray(Charsets.UTF_8).size <= MAX_PRIVATE_KEY_BYTES) {
            privateKey = value
            problem = null
        } else {
            privateKey = ""
            problem = Message(R.string.key_import_too_large, listOf(MAX_PRIVATE_KEY_BYTES / 1024))
        }
    }

    /** The opener must use the application resolver, so an old Activity is never retained. */
    fun readDocument(open: () -> InputStream?) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                privateKey = withContext(Dispatchers.IO) {
                    open()?.use { input ->
                        val bytes = ByteArray(MAX_PRIVATE_KEY_BYTES + 1)
                        var size = 0
                        while (size < bytes.size) {
                            val read = input.read(bytes, size, bytes.size - size)
                            if (read < 0) break
                            size += read
                        }
                        require(size <= MAX_PRIVATE_KEY_BYTES)
                        String(bytes, 0, size, Charsets.UTF_8)
                    } ?: error("document unavailable")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                privateKey = ""
                problem = Message(R.string.key_import_file_error, listOf(MAX_PRIVATE_KEY_BYTES / 1024))
            } finally {
                busy = false
            }
        }
    }

    fun submit(label: String, replacementKeyId: String?) {
        if (busy || privateKey.isBlank()) return
        busy = true
        problem = null
        scope.launch {
            try {
                performImport(label, privateKey, passphrase, replacementKeyId)
                privateKey = ""
                passphrase = ""
                completed = true
            } catch (e: CoreException) {
                passphrase = ""
                problem = problemFor(e).body
            } finally {
                busy = false
            }
        }
    }

    fun clear() {
        if (busy) return
        privateKey = ""
        passphrase = ""
        problem = null
        completed = false
    }
}
