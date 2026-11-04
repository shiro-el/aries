package io.github.shiroel.aries.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class SettingsOperationLifetimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun str(id: Int) = compose.activity.getString(id)

    private class PausedKeyWork : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun finish() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    // D90: recreation while decryption waits must retain busy state and close the new dialog on success.
    @Test fun importingKeyDeliversItsResultToTheRecreatedDialog() {
        val core = FakeCore().apply { ensureKey() }
        val work = PausedKeyWork()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val model = HostsModel(core, scope, keyWork = work)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { AriesApp(model) } }
            compose.openKeyDetails()
            compose.onNodeWithText(str(R.string.key_import_button)).performScrollTo().performClick()
            compose.onNodeWithTag("key-import-label").performTextInput("work key")
            compose.onNodeWithTag("key-import-private").performTextInput("private test key")
            compose.onNodeWithTag("key-import-save").performClick()
            compose.onNodeWithText(str(R.string.key_import_busy)).assertExists()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(str(R.string.key_import_busy)).assertExists()
            compose.runOnIdle { work.finish() }
            compose.waitUntil(5_000) { core.keys().size == 2 }
            compose.onNodeWithTag("key-import-private").assertDoesNotExist()
            assertEquals(listOf("", "work key"), model.keys.map { it.label })
        } finally {
            scope.cancel()
        }
    }
    // D91: a delayed generation failure must keep the label through recreation, then allow retry.
    @Test fun failedAdditionalKeyCreationKeepsItsLabelUntilASuccessfulRetry() {
        val fake = FakeCore().apply { ensureKey() }
        var fail = true
        val core = object : AriesCoreInterface by fake {
            override fun createKey(label: String): KeyInfo {
                if (fail) throw CoreException.KeyLocked()
                return fake.createKey(label)
            }
        }
        val work = PausedKeyWork()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val model = HostsModel(core, scope, keyWork = work)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { AriesApp(model) } }
            compose.openKeyDetails()
            val field = compose.onNode(hasSetTextAction() and hasText(str(R.string.key_new_label)))
            field.performScrollTo().performTextInput("bastion")
            compose.onNodeWithText(str(R.string.key_new_button)).performScrollTo().performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { work.finish() }
            compose.onNodeWithText(str(R.string.ok_button)).performClick()
            field.assertTextContains("bastion")
            compose.runOnIdle { fail = false }
            compose.onNodeWithText(str(R.string.key_new_button)).performScrollTo().performClick()
            compose.runOnIdle { work.finish() }
            compose.waitUntil(5_000) { model.keys.size == 2 }
            field.assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(""),
            ))
            assertEquals(listOf("", "bastion"), model.keys.map { it.label })
        } finally {
            scope.cancel()
        }
    }

}
