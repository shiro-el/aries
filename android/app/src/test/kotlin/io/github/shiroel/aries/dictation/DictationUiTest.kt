package io.github.shiroel.aries.dictation

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.KeyProtection
import io.github.shiroel.aries.core.KeyVault
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class DictationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val vault = object : KeyVault {
        override fun seal(plaintext: ByteArray, protection: KeyProtection) = plaintext.reversedArray()
        override fun open(sealed: ByteArray, protection: KeyProtection) = sealed.reversedArray()
    }
    private fun label(id: Int) = context.getString(id)
    private class Recorder : AudioRecorder {
        var started = false
        var cancelled = false
        val file = File.createTempFile("dictation-ui", ".m4a").apply { writeText("fake") }
        override fun start(onLimit: () -> Unit) { started = true }
        override fun stop() = file
        override fun cancel() { cancelled = true; file.delete() }
    }

    @Test fun noKeyHidesTheMicrophone() {
        val store = DictationStore(context, vault).apply { clearKey() }
        compose.setContent { MaterialTheme { DictationInput(emptyList(), "", {}, store = store) } }
        compose.onNodeWithText(label(R.string.dictation_start_button)).assertDoesNotExist()
    }

    @Test fun disclosureThenPermissionThenRecordingAndTranscriptOnly() {
        val store = DictationStore(context, vault).apply { saveKey("fake-key") }
        val recorder = Recorder()
        var permission: ((Boolean) -> Unit)? = null
        var result = ""
        var uploads = 0
        compose.setContent {
            MaterialTheme { DictationInput(listOf("home"), "", { result = it }, store, recorder,
                Transcriber { _, _, _, _ -> uploads++; "고쳐서 보낼 문장" },
                requestPermission = { permission = it }) }
        }
        compose.onNodeWithText(label(R.string.dictation_start_button)).performClick()
        compose.onNodeWithText(label(R.string.dictation_disclosure)).assertIsDisplayed()
        assertFalse(recorder.started)
        compose.onNodeWithText(label(R.string.dictation_record_button)).performClick()
        assertNotNull(permission)
        assertFalse(recorder.started)
        compose.runOnIdle { permission!!(true) }
        compose.onNodeWithText(label(R.string.dictation_stop_button)).performClick()
        compose.waitUntil(5_000) { result.isNotEmpty() }
        assertEquals("고쳐서 보낼 문장", result)
        assertEquals(1, uploads)
        compose.waitForIdle()
        assertFalse(recorder.file.exists())
        store.clearKey()
    }

    @Test fun deniedPermissionDoesNotRecordOrUpload() {
        val store = DictationStore(context, vault).apply { saveKey("fake-key") }
        val recorder = Recorder()
        compose.setContent {
            MaterialTheme { DictationInput(emptyList(), "", {}, store, recorder,
                Transcriber { _, _, _, _ -> error("must not upload") }, requestPermission = { it(false) }) }
        }
        compose.onNodeWithText(label(R.string.dictation_start_button)).performClick()
        compose.onNodeWithText(label(R.string.dictation_record_button)).performClick()
        compose.onNodeWithText(label(R.string.dictation_permission_denied)).assertExists()
        assertFalse(recorder.started)
        store.clearKey()
        recorder.cancel()
    }
    @Test fun settingsAcceptKeyWithoutShowingSavedSecretAndPersistOptions() {
        val store = DictationStore(context, vault).apply { clearKey(); saveOptions(DictationOptions()) }
        compose.setContent { MaterialTheme { DictationSettings(store) } }
        compose.onNodeWithText(label(R.string.dictation_key_label)).performTextInput("saved-secret")
        compose.onNodeWithText(label(R.string.dictation_key_save_button)).performClick()
        compose.onNodeWithText("saved-secret").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.dictation_key_saved)).assertExists()
        assertEquals("saved-secret", store.readKey())
        compose.onNodeWithText(label(R.string.dictation_language_label)).performTextInput("ko")
        compose.onNodeWithText(label(R.string.dictation_vocabulary_label)).performTextInput("Aries, Herdr")
        compose.onNodeWithTag("dictation-model:TURBO").performClick()
        compose.onNodeWithText(label(R.string.dictation_context_label)).performClick()
        compose.runOnIdle {
            assertEquals(DictationOptions("ko", DictationModel.TURBO, "Aries, Herdr", true), store.options())
        }
        compose.onNodeWithText(label(R.string.dictation_key_remove_button)).performClick()
        assertFalse(store.hasKey)
    }

    @Test fun cancelDiscardsAudioAndNeverAppliesAnInFlightResult() {
        val store = DictationStore(context, vault).apply { saveKey("fake-key") }
        val recorder = Recorder()
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        var result = ""
        compose.setContent {
            MaterialTheme { DictationInput(emptyList(), "", { result = it }, store, recorder,
                Transcriber { _, _, _, _ ->
                    entered.countDown()
                    finish.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    returned.countDown()
                    "discard this"
                }, requestPermission = { it(true) }) }
        }
        try {
            compose.onNodeWithText(label(R.string.dictation_start_button)).performClick()
            compose.onNodeWithText(label(R.string.dictation_record_button)).performClick()
            compose.onNodeWithText(label(R.string.dictation_stop_button)).performClick()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.onNodeWithText(label(R.string.cancel_button)).performClick()
            assertFalse(recorder.file.exists())
            finish.countDown()
            assertTrue(returned.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.waitForIdle()
            assertEquals("", result)
            compose.onNodeWithText(label(R.string.dictation_start_button)).assertExists()
        } finally { finish.countDown(); store.clearKey(); recorder.cancel() }
    }

    @Test fun rateLimitRetainsAudioForAnExplicitRetryAndCancelDeletesIt() {
        val store = DictationStore(context, vault).apply { saveKey("fake-key") }
        val recorder = Recorder()
        var uploads = 0
        compose.setContent {
            MaterialTheme { DictationInput(emptyList(), "", {}, store, recorder,
                Transcriber { _, _, _, _ -> uploads++; throw DictationFailure.RateLimited(60) },
                requestPermission = { it(true) }) }
        }
        compose.onNodeWithText(label(R.string.dictation_start_button)).performClick()
        compose.onNodeWithText(label(R.string.dictation_record_button)).performClick()
        compose.onNodeWithText(label(R.string.dictation_stop_button)).performClick()
        compose.waitUntil(5_000) { uploads == 1 }
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.dictation_retry_button)).assertIsNotEnabled()
        assertTrue(recorder.file.exists())
        assertEquals(1, uploads)
        compose.onNodeWithText(label(R.string.cancel_button)).performClick()
        assertFalse(recorder.file.exists())
        store.clearKey()
    }

}
