package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.R
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class HostEditTest {
    @get:Rule val compose = createComposeRule()

    private fun show(core: FakeCore, standalone: Boolean = false) {
        val capturedHost = core.hosts().first()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            MaterialTheme {
                if (standalone) Column { HostEditSection(model, capturedHost) } else AriesApp(model)
            }
        }
        if (!standalone) compose.openHostActionsIfClosed("h1")
        val edit = compose.onNodeWithTag("host-edit:h1")
        if (standalone) edit.performClick() else edit.performScrollTo().performClick()
    }

    @Test fun editsNameAndAddressInPlaceAndReopensWithLatestValues() {
        val core = FakeCore()
        val original = core.addHost("home", Endpoint.Ssh("old.example", 22u, "me"))
        core.trustHostKey(original.id, "SHA256:old")
        show(core)
        compose.onNodeWithTag("host-edit-name").assertTextContains("home")
        compose.onNodeWithTag("host-edit-name").performTextReplacement("work")
        compose.onNodeWithTag("host-edit-address").performTextReplacement("new.example")
        compose.onNodeWithTag("host-edit-port").performTextReplacement("2222")
        compose.onNodeWithTag("host-edit-user").performTextReplacement("dev")
        compose.onNodeWithTag("host-edit-save").performScrollTo().performClick()
        compose.waitForIdle()
        val changed = core.hosts().single()
        assertEquals(original.id, changed.id)
        assertEquals("work", changed.name)
        assertEquals(Endpoint.Ssh("new.example", 2222u, "dev"), changed.endpoint)
        assertEquals(null, changed.hostKey)
        compose.onNodeWithTag("host-edit:h1").performScrollTo().performClick()
        compose.onNodeWithTag("host-edit-name").assertTextContains("work")
        compose.onNodeWithTag("host-edit-address").assertTextContains("new.example")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun cancelDoesNotWriteAndInvalidOrDuplicateValuesCannotSave() {
        val core = FakeCore()
        val original = core.addHost("home", Endpoint.Ssh("old.example", 22u, "me"))
        core.addHost("taken", Endpoint.Ssh("other", 22u, "me"))
        show(core)
        compose.onNodeWithTag("host-edit-name").performTextReplacement("taken")
        compose.onNodeWithTag("host-edit-save").assertIsNotEnabled()
        System.getenv("ARIES_ONBOARDING_RENDER_DIR")?.let { directory ->
            java.io.File(directory, "host-name-error.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("host-edit-name").performTextReplacement("renamed")
        for (invalid in listOf("0", "65536", "abc")) {
            compose.onNodeWithTag("host-edit-port").performTextReplacement(invalid)
            compose.onNodeWithTag("host-edit-save").assertIsNotEnabled()
        }
        compose.onNodeWithTag("host-edit-port").performTextReplacement("22")
        compose.onNodeWithTag("host-edit-address").performTextReplacement(" ")
        compose.onNodeWithTag("host-edit-save").assertIsNotEnabled()
        compose.onNodeWithTag("host-edit-cancel").performScrollTo().performClick()
        assertEquals(original, core.hosts().first())
        compose.onNodeWithTag("host-edit-save").assertDoesNotExist()
    }

    @Test fun settingsSlotResolvesLatestHostByIdAndNameOnlyEditKeepsTrust() {
        val core = FakeCore()
        val original = core.addHost("home", Endpoint.Ssh("old.example", 22u, "me"))
        core.trustHostKey(original.id, "SHA256:trusted")
        show(core, standalone = true)
        compose.onNodeWithTag("host-edit-name").performTextReplacement("renamed")
        compose.onNodeWithTag("host-edit-save").performClick()
        compose.waitForIdle()
        assertEquals("SHA256:trusted", core.hosts().single().hostKey)
        compose.onNodeWithTag("host-edit:h1").performClick()
        compose.onNodeWithTag("host-edit-name").assertTextContains("renamed")
    }

    @Test fun failedSaveKeepsOriginalAndUserInputForRetry() {
        val core = FakeCore()
        val original = core.addHost("home", Endpoint.Ssh("old.example", 22u, "me"))
        core.editFailure = CoreException.Storage("disk full")
        show(core)
        compose.onNodeWithTag("host-edit-name").performTextReplacement("retry this")
        compose.onNodeWithTag("host-edit-save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(original, core.hosts().single())
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(io.github.shiroel.aries.R.string.ok_button)).performClick()
        compose.onNodeWithTag("host-edit-name").assertTextContains("retry this")
        compose.onNodeWithTag("host-edit-save").assertIsEnabled()
    }
    // Regression: a core-side add rejection must leave the user's address/user drafts available for correction.
    @Test fun failedAddKeepsTheDraftForCorrection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fake = FakeCore().apply { ensureKey() }
        val core = object : AriesCoreInterface by fake {
            override fun addHost(name: String, endpoint: Endpoint): Host = throw CoreException.InvalidHost("rejected")
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope).also { it.startAddingHost() } }
            MaterialTheme { AriesApp(model) }
        }
        compose.onNodeWithText(context.getString(R.string.host_add_address)).performScrollTo().performTextReplacement("desk.example")
        compose.onNodeWithText(context.getString(R.string.host_add_user)).performScrollTo().performTextReplacement("me")
        compose.onNodeWithText(context.getString(R.string.host_add_button)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.ok_button)).performClick()
        compose.onNodeWithText(context.getString(R.string.host_add_address)).assertTextContains("desk.example")
        compose.onNodeWithText(context.getString(R.string.host_add_user)).assertTextContains("me")
    }

}
