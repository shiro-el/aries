package io.github.shiroel.aries.guidance

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.KeyInstall
import io.github.shiroel.aries.R
import io.github.shiroel.aries.ui.AriesApp
import io.github.shiroel.aries.ui.FakeCore
import io.github.shiroel.aries.ui.HostsModel
import io.github.shiroel.aries.ui.openConsoleDrawer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppGuidanceTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("guidance-test", Context.MODE_PRIVATE)

    private fun screenshot(name: String) {
        System.getenv("ARIES_ONBOARDING_RENDER_DIR")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun firstConnectionCreatesKeyInlineAndRequiresConnectionBeforeBeaconSetup() {
        prefs.edit().clear().commit()
        val state = AppGuidanceState(prefs, 1, "0.1")
        val core = FakeCore().apply {
            requireTrust = "SHA256:abc"
            failFor["h1"] = io.github.shiroel.aries.core.CoreException.AuthRejected()
        }
        val api = object : AriesCoreInterface by core {
            override suspend fun installKeyWithPassword(hostId: String, password: String): KeyInstall {
                return core.installKeyWithPassword(hostId, password).also { core.failFor.remove(hostId) }
            }
        }
        var permissionRequested = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(api, scope) }
            AriesApp(model, guidance = state, allowNotifications = { permissionRequested = true; false })
        }
        screenshot("welcome")
        compose.onNodeWithTag("onboarding-manual").performClick()
        screenshot("inline-key")
        compose.onNodeWithText(context.getString(R.string.key_create_button)).performScrollTo().performClick()
        screenshot("manual-key-and-host")
        compose.onNodeWithTag("onboarding-connected").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.host_add_address)).performScrollTo().performTextReplacement("desk.example")
        compose.onNodeWithText(context.getString(R.string.host_add_user)).performScrollTo().performTextReplacement("me")
        compose.onNodeWithText(context.getString(R.string.host_add_button)).performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-connected").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.host_key_trust_button)).performClick()
        compose.onNodeWithTag("onboarding-connected").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.host_install_key_button)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.key_install_password)).performTextReplacement("one-time password")
        compose.onNodeWithText(context.getString(R.string.key_install_button)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("h1" to "one-time password"), core.passwordsTried)
        }
        compose.onNodeWithText(context.getString(R.string.ok_button)).performClick()
        compose.onNodeWithTag("onboarding-connected").assertExists()
        compose.onNodeWithText(context.getString(R.string.beacon_install_button)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.beacon_reinstall_button)).assertExists()
        compose.onNodeWithTag("onboarding-permission").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, permissionRequested) }
        screenshot("connected-beacon")
        compose.onNodeWithTag("onboarding-finish").performScrollTo().performClick()
        assertEquals(null, AppGuidanceState(prefs, 1, "0.1").onboardingStep)
    }

    @Test fun qrRegistrationContinuesAfterImportingTheFirstKeyInline() {
        prefs.edit().clear().commit()
        val state = AppGuidanceState(prefs, 1, "0.1")
        val core = FakeCore()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model, guidance = state)
        }
        compose.onNodeWithTag("onboarding-qr").performClick()
        assertEquals(1, state.onboardingStep)
        compose.onNodeWithText(context.getString(R.string.key_import_button)).performScrollTo().performClick()
        compose.onNodeWithTag("key-import-private").performTextReplacement("private test key")
        compose.onNodeWithTag("key-import-save").performClick()
        assertEquals("private test key", core.importedKeys.single().second)
        compose.onNodeWithTag("host-pair-scan").assertExists()
        compose.onNodeWithText(context.getString(R.string.host_add_address)).assertDoesNotExist()
        screenshot("qr-after-import")
        compose.onNodeWithTag("onboarding-skip").performClick()
        assertEquals(null, AppGuidanceState(prefs, 1, "0.1").onboardingStep)
    }

    @Test fun bundledChangesAppearOnceAfterEachVersionChange() {
        prefs.edit().clear().commit()
        val current = mutableStateOf(AppGuidanceState(prefs, 1, "0.1").also { it.finishOnboarding() })
        compose.setContent { MaterialTheme { AppGuidance(current.value) {} } }
        compose.onNodeWithTag("whats-new-dialog").assertDoesNotExist()
        compose.runOnIdle { current.value = AppGuidanceState(prefs, 2, "0.2") }
        compose.onNodeWithTag("whats-new-dialog").assertExists()
        compose.onNodeWithTag("whats-new-dismiss").performClick()
        compose.runOnIdle { current.value = AppGuidanceState(prefs, 2, "0.2") }
        compose.onNodeWithTag("whats-new-dialog").assertDoesNotExist()
        compose.runOnIdle { current.value = AppGuidanceState(prefs, 3, "0.3") }
        compose.onNodeWithTag("whats-new-dialog").assertExists()
    }

    @Test fun updateFeedShowsOnlyHigherVersionCodesAndOpensApkInBrowser() {
        prefs.edit().clear().commit()
        val state = AppGuidanceState(prefs, 10, "0.10").also { it.finishOnboarding() }
        val code = AtomicInteger(11)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/release.json") { exchange ->
            val body = """{"versionCode":${code.get()},"versionName":"0.11","apkUrl":"https://example.test/aries.apk","notes":"A new release"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            state.setUpdateUrl("http://127.0.0.1:${server.address.port}/release.json")
            compose.setContent { MaterialTheme { AppGuidance(state) {} } }
            runBlocking { state.checkForUpdate() }
            compose.onNodeWithTag("app-update-banner").assertExists()
            compose.onNodeWithTag("app-update-download").performClick()
            val opened = shadowOf(context as Application).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals("https://example.test/aries.apk", opened.dataString)
            screenshot("update-banner")
            compose.onNodeWithTag("app-update-dismiss").performClick()
            runBlocking { state.checkForUpdate() }
            compose.onNodeWithTag("app-update-banner").assertDoesNotExist()
            val recreated = AppGuidanceState(prefs, 10, "0.10")
            runBlocking { recreated.checkForUpdate() }
            assertEquals(null, recreated.bannerRelease)
            code.set(12)
            runBlocking { state.checkForUpdate() }
            compose.onNodeWithTag("app-update-banner").assertExists()
            code.set(10)
            runBlocking { state.checkForUpdate() }
            compose.onNodeWithTag("app-update-banner").assertDoesNotExist()
            code.set(9)
            runBlocking { state.checkForUpdate() }
            compose.onNodeWithTag("app-update-banner").assertDoesNotExist()
        } finally {
            server.stop(0)
        }
    }

    @Test fun settingsSaveTheFeedAndReopenTheConnectionGuide() {
        prefs.edit().clear().commit()
        val state = AppGuidanceState(prefs, 10, "0.10").also { it.finishOnboarding() }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val hosts = remember { HostsModel(FakeCore(), scope) }
            AriesApp(hosts, guidance = state)
        }
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("app-guidance")
        compose.onNodeWithTag("app-update-url").performScrollTo().performTextReplacement("https://example.test/release.json")
        compose.onNodeWithTag("app-update-save").performScrollTo().performClick()
        assertEquals("https://example.test/release.json", AppGuidanceState(prefs, 10, "0.10").updateUrl)
        compose.onNodeWithTag("guidance-open").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-manual").assertExists()
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("app-guidance")
        compose.onNodeWithTag("whats-new-open").performScrollTo().performClick()
        compose.onNodeWithTag("whats-new-dialog").assertExists()
    }

    @Test fun invalidFeedsAndHttpFailuresHideTheBannerAndAllowRetry() {
        prefs.edit().clear().commit()
        val state = AppGuidanceState(prefs, 10, "0.10").also { it.finishOnboarding() }
        val good = """{"versionCode":11,"versionName":"0.11","apkUrl":"https://example.test/app.apk","notes":"New features"}"""
        val response = AtomicReference(good)
        val status = AtomicInteger(200)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = response.get().toByteArray()
            exchange.sendResponseHeaders(status.get(), body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/release.json"
            state.setUpdateUrl(url)
            assertEquals(false, state.setUpdateUrl("file:///tmp/release.json"))
            assertEquals(url, state.updateUrl)
            compose.setContent { MaterialTheme { AppGuidance(state) { AppGuidanceSettings(state) } } }
            runBlocking { state.checkForUpdate() }
            compose.onNodeWithTag("app-update-banner").assertExists()
            for ((httpStatus, body) in listOf(
                503 to good,
                200 to "not json",
                200 to good.replace("https://example.test/app.apk", "javascript:alert(1)"),
                200 to """{"versionCode":11}""",
            )) {
                status.set(httpStatus)
                response.set(body)
                runBlocking { state.checkForUpdate() }
                compose.onNodeWithTag("app-update-banner").assertDoesNotExist()
                compose.onNodeWithText(context.getString(R.string.app_update_failed)).assertExists()
            }
            status.set(200)
            response.set(good)
            compose.onNodeWithTag("app-update-check").performClick()
            compose.waitUntil(5_000) { state.updateCheck == AppUpdateCheck.AVAILABLE }
            compose.onNodeWithTag("app-update-banner").assertExists()
        } finally {
            server.stop(0)
        }
    }

    // Regression: upgrading a pre-guide install must not hide its saved Hosts behind welcome.
    @Test fun existingHostsCompleteOnboardingAndAnAbandonedConnectStepRestartsAtWelcome() {
        prefs.edit().clear().putInt("onboarding_step", 1).commit()
        assertEquals(0, AppGuidanceState(prefs, 1, "0.1").onboardingStep)
        assertEquals(null, AppGuidanceState(prefs, 1, "0.1", hasHosts = true).onboardingStep)
        assertEquals(null, AppGuidanceState(prefs, 1, "0.1").onboardingStep)
    }

    // Regression: connecting an unrelated Host or entering Settings must not advance or overlay a guide.
    @Test fun guideFollowsOnlyItsSubmittedHost() {
        prefs.edit().clear().commit()
        val state = AppGuidanceState(prefs, 1, "0.1")
        val core = FakeCore().apply { ensureKey() }
        val other = core.addHost("Other", io.github.shiroel.aries.core.Endpoint.Ssh("other.example", 22u, "me"))
        lateinit var model: HostsModel
        compose.setContent {
            val scope = rememberCoroutineScope()
            model = remember { HostsModel(core, scope) }
            AriesApp(model, guidance = state)
        }
        compose.onNodeWithTag("onboarding-manual").performClick()
        compose.runOnIdle {
            model.addHost("New", "new.example", 22, "me")
            model.connect(other)
        }
        compose.waitForIdle()
        assertEquals(1, state.onboardingStep)
        compose.onNodeWithTag("onboarding-connected").assertDoesNotExist()
        compose.runOnIdle { model.openSettings() }
        compose.onNodeWithTag("onboarding-skip").assertDoesNotExist()
        compose.runOnIdle { model.back(); model.connect(model.hosts.last()) }
        compose.onNodeWithTag("onboarding-connected").assertExists()
        assertEquals(2, state.onboardingStep)
    }

}
