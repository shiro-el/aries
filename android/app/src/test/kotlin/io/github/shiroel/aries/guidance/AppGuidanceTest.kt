package io.github.shiroel.aries.guidance

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class AppGuidanceTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("guidance-test", Context.MODE_PRIVATE)

    @Test fun firstLaunchResumesItsStepAndSkipPersistsAcrossRelaunch() {
        prefs.edit().clear().commit()
        val first = AppGuidanceState(prefs, 1, "0.1")
        val current = first
        compose.setContent { MaterialTheme { AppGuidance(current) {} } }
        compose.onNodeWithTag("onboarding-step-0").assertExists()
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-step-1").assertExists()
        val relaunched = AppGuidanceState(prefs, 1, "0.1")
        assertEquals(1, relaunched.onboardingStep)
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.onNodeWithTag("onboarding-dialog").assertDoesNotExist()
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
            compose.onNodeWithTag("app-update-banner").performClick()
            val opened = shadowOf(context as Application).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals("https://example.test/aries.apk", opened.dataString)
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

    @Test fun settingsSaveTheFeedAndReopenGuidanceWithNotificationPermissionAction() {
        prefs.edit().clear().commit()
        val state = AppGuidanceState(prefs, 10, "0.10").also { it.finishOnboarding() }
        var permissionRequested = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            val hosts = remember { HostsModel(FakeCore(), scope) }
            AriesApp(hosts, guidance = state, allowNotifications = { permissionRequested = true; false })
        }
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("app-guidance")
        compose.onNodeWithTag("app-update-url").performScrollTo().performTextReplacement("https://example.test/release.json")
        compose.onNodeWithTag("app-update-save").performScrollTo().performClick()
        assertEquals("https://example.test/release.json", AppGuidanceState(prefs, 10, "0.10").updateUrl)
        compose.onNodeWithTag("guidance-open").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-step-2").assertExists()
        compose.onNodeWithTag("onboarding-permission").performClick()
        compose.runOnIdle { assertEquals(true, permissionRequested) }
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-dialog").assertDoesNotExist()
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

}
