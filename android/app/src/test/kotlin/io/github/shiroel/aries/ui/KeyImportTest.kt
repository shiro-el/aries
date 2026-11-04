package io.github.shiroel.aries.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.shiroel.aries.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** UI -> core seam, including the system document picker. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class KeyImportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun str(id: Int) = compose.activity.getString(id)

    private fun show(withDefaultKey: Boolean = true): FakeCore {
        val core = FakeCore().apply { if (withDefaultKey) ensureKey() }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, keyWork = Dispatchers.Unconfined + inKeyWork.asContextElement(true)) }
            MaterialTheme { AriesApp(model) }
        }
        compose.openKeyDetails()
        compose.onNodeWithText(str(R.string.key_import_button)).performScrollTo().performClick()
        return core
    }

    @Test fun pastedKeyAndPassphraseAreImportedOffMainAndClearedAfterSuccess() {
        val core = show()
        compose.onNodeWithTag("key-import-label").performTextInput("work key")
        compose.onNodeWithTag("key-import-private").performTextInput("private test key")
        compose.onNodeWithTag("key-import-passphrase").performTextInput("one-time passphrase")
        compose.onNodeWithTag("key-import-save").performClick()
        compose.waitUntil(5_000) { core.keys().size == 2 }
        assertEquals(Triple("work key", "private test key", "one-time passphrase"), core.importedKeys.single())
        assertEquals(emptyList<String>(), core.offKeyWork)
        compose.onNodeWithTag("key-import-private").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.key_import_button)).performScrollTo().performClick()
        compose.onNodeWithTag("key-import-private").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("key-import-passphrase").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun documentPickerImportsTheSelectedFile() {
        val core = show()
        compose.onNodeWithTag("key-import-label").performTextInput("from file")
        compose.onNodeWithText(str(R.string.key_import_file_button)).performScrollTo().performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        val uri = Uri.parse("content://documents/private-key")
        val contents = "private key from document\n"
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(contents.toByteArray()))
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri)) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("key-import-save").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("key-import-save").performClick()
        compose.waitUntil(5_000) { core.keys().size == 2 }
        assertEquals(Triple("from file", contents, ""), core.importedKeys.single())
    }

    @Test fun failedImportExplainsTheProblemAndAllowsARetryWithAFreshPassphrase() {
        val core = show()
        core.importFailure = io.github.shiroel.aries.core.CoreException.InvalidPrivateKey()
        compose.onNodeWithTag("key-import-label").performTextInput("retry key")
        compose.onNodeWithTag("key-import-private").performTextInput("keep this key for retry")
        compose.onNodeWithTag("key-import-passphrase").performTextInput("wrong")
        compose.onNodeWithTag("key-import-save").performClick()
        compose.onNodeWithText(str(R.string.key_import_invalid)).assertExists()
        compose.onNodeWithTag("key-import-passphrase").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(1, core.keys().size)
        compose.runOnIdle { core.importFailure = null }
        compose.onNodeWithTag("key-import-passphrase").performTextInput("correct")
        compose.onNodeWithTag("key-import-save").performClick()
        compose.waitUntil(5_000) { core.keys().size == 2 }
        assertEquals(Triple("retry key", "keep this key for retry", "correct"), core.importedKeys.single())
    }

    @Test fun oversizeAndUnreadableDocumentsHaveASafeExplanation() {
        val core = show()
        compose.onNodeWithTag("key-import-label").performTextInput("file")
        for ((name, bytes) in listOf("oversize" to ByteArray(64 * 1024 + 1), "unreadable" to null)) {
            compose.onNodeWithText(str(R.string.key_import_file_button)).performScrollTo().performClick()
            val request = shadowOf(compose.activity).nextStartedActivityForResult
            val uri = Uri.parse("content://documents/$name")
            if (bytes != null) shadowOf(compose.activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
            compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.key_import_file_error, MAX_PRIVATE_KEY_BYTES / 1024)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("key-import-save").assertIsNotEnabled()
        }
        assertTrue(core.importedKeys.isEmpty())
    }

    @Test fun cancellingImportDiscardsSecretDrafts() {
        val core = show()
        compose.onNodeWithTag("key-import-private").performTextInput("discard private key")
        compose.onNodeWithTag("key-import-passphrase").performTextInput("discard passphrase")
        compose.onNodeWithText(str(R.string.cancel_button)).performClick()
        compose.onNodeWithText(str(R.string.key_import_button)).performScrollTo().performClick()
        compose.onNodeWithTag("key-import-private").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("key-import-passphrase").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertTrue(core.importedKeys.isEmpty())
    }

    @Test fun firstKeyCanBeImportedWithoutCreatingAGeneratedKey() {
        val core = show(withDefaultKey = false)
        compose.onNodeWithTag("key-import-private").performTextInput("first private key")
        compose.onNodeWithTag("key-import-save").performClick()
        compose.waitUntil(5_000) { core.publicKey() != null }
        assertEquals(listOf("default"), core.keys().map { it.id })
        compose.onNodeWithText(str(R.string.key_biometric_label)).assertExists()
    }

    @Test fun oversizedPasteDoesNotLeaveAStalePrivateKeyReadyToImport() {
        val core = show()
        compose.onNodeWithTag("key-import-label").performTextInput("oversized")
        compose.onNodeWithTag("key-import-private").performTextInput("previous private key")
        compose.onNodeWithTag("key-import-save").assertIsEnabled()
        compose.onNodeWithTag("key-import-private").performTextReplacement("x".repeat(64 * 1024 + 1))
        compose.onNodeWithTag("key-import-save").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.key_import_too_large, MAX_PRIVATE_KEY_BYTES / 1024)).assertExists()
        assertTrue(core.importedKeys.isEmpty())
    }

}
