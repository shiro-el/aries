package io.github.shiroel.aries.guidance

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppUpdateCheck { IDLE, CHECKING, CURRENT, AVAILABLE, FAILED }

/** Phone-local guidance; skipping never changes the Host's configuration. */
class AppGuidanceState(
    private val preferences: SharedPreferences,
    val versionCode: Int,
    val versionName: String,
    hasHosts: Boolean = false,
) {
    var onboardingStep: Int? by mutableStateOf(
        if (hasHosts || preferences.getBoolean("onboarding_complete", false)) null else 0,
    )
        private set

    val releaseNotes: Int = when (versionName.substringBefore('-')) {
        "0.1.0" -> io.github.shiroel.aries.R.string.whats_new_0_1_0_body
        else -> io.github.shiroel.aries.R.string.whats_new_body
    }

    private val versionIdentity = "$versionCode:$versionName"
    var showWhatsNew by mutableStateOf(
        preferences.getString("last_version", null)?.let { it != versionIdentity } ?: false,
    )
        private set

    var guideHostId: String? by mutableStateOf(null)
        private set

    fun followHost(hostId: String) { guideHostId = hostId }

    init {
        preferences.edit().remove("onboarding_step").apply()
        if (hasHosts) finishOnboarding()
        // A fresh install gets onboarding; it has not upgraded from an older app.
        if (!preferences.contains("last_version")) {
            preferences.edit().putString("last_version", versionIdentity).apply()
        }
    }

    fun openWhatsNew() { showWhatsNew = true }

    fun dismissWhatsNew() {
        preferences.edit().putString("last_version", versionIdentity).apply()
        showWhatsNew = false
    }

    fun openOnboarding() {
        preferences.edit().putBoolean("onboarding_complete", false).remove("onboarding_step").apply()
        guideHostId = null
        onboardingStep = 0
    }

    var updateUrl by mutableStateOf((preferences.getString("update_url", DEFAULT_APP_UPDATE_URL) ?: DEFAULT_APP_UPDATE_URL).let {
        if (it == DEV_APP_UPDATE_URL && !io.github.shiroel.aries.BuildConfig.ARIES_DEV_UPDATES) "" else it
    })
        private set
    var release: AppRelease? by mutableStateOf(null)
        private set
    var updateCheck by mutableStateOf(AppUpdateCheck.IDLE)
        private set

    private var dismissedUpdate by mutableStateOf(preferences.getString("dismissed_update", null))
    val bannerRelease: AppRelease?
        get() = release?.takeUnless { dismissedUpdate == "$updateUrl:${it.versionCode}" }

    fun dismissUpdate() {
        val available = release ?: return
        dismissedUpdate = "$updateUrl:${available.versionCode}"
        preferences.edit().putString("dismissed_update", dismissedUpdate).apply()
    }

    fun setUpdateUrl(value: String): Boolean {
        val url = value.trim()
        if (url.isNotEmpty() && !isAppUpdateUrl(url)) return false
        preferences.edit().putString("update_url", url).apply()
        updateUrl = url
        release = null
        updateCheck = AppUpdateCheck.IDLE
        return true
    }

    suspend fun checkForUpdate() {
        if (updateUrl.isBlank() || updateCheck == AppUpdateCheck.CHECKING) return
        val requestedUrl = updateUrl
        updateCheck = AppUpdateCheck.CHECKING
        try {
            val found = withContext(Dispatchers.IO) { HttpAppUpdates().fetch(requestedUrl) }
            if (requestedUrl != updateUrl) return
            release = found.takeIf { it.versionCode > versionCode.toLong() }
            updateCheck = if (release == null) AppUpdateCheck.CURRENT else AppUpdateCheck.AVAILABLE
        } catch (e: CancellationException) {
            if (requestedUrl == updateUrl) updateCheck = AppUpdateCheck.IDLE
            throw e
        } catch (_: Exception) {
            if (requestedUrl == updateUrl) {
                release = null
                updateCheck = AppUpdateCheck.FAILED
            }
        }
    }

    fun nextOnboardingStep() {
        val step = onboardingStep ?: return
        if (step == 2) finishOnboarding() else {
            onboardingStep = step + 1
        }
    }

    fun finishOnboarding() {
        preferences.edit().putBoolean("onboarding_complete", true).remove("onboarding_step").apply()
        onboardingStep = null
        guideHostId = null
    }
}
