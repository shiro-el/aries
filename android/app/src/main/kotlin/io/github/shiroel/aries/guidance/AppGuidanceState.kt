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
) {
    var onboardingStep: Int? by mutableStateOf(
        if (preferences.getBoolean("onboarding_complete", false)) null
        else preferences.getInt("onboarding_step", 0).coerceIn(0, 2),
    )
        private set

    private val versionIdentity = "$versionCode:$versionName"
    var showWhatsNew by mutableStateOf(
        preferences.getString("last_version", null)?.let { it != versionIdentity } ?: false,
    )
        private set

    init {
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

    fun openOnboarding() { onboardingStep = 0 }

    var updateUrl by mutableStateOf(preferences.getString("update_url", DEFAULT_APP_UPDATE_URL) ?: DEFAULT_APP_UPDATE_URL)
        private set
    var release: AppRelease? by mutableStateOf(null)
        private set
    var updateCheck by mutableStateOf(AppUpdateCheck.IDLE)
        private set

    fun setUpdateUrl(value: String): Boolean {
        val url = value.trim()
        if (!isAppUpdateUrl(url)) return false
        preferences.edit().putString("update_url", url).apply()
        updateUrl = url
        release = null
        updateCheck = AppUpdateCheck.IDLE
        return true
    }

    suspend fun checkForUpdate() {
        if (updateCheck == AppUpdateCheck.CHECKING) return
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
            preferences.edit().putInt("onboarding_step", step + 1).apply()
            onboardingStep = step + 1
        }
    }

    fun finishOnboarding() {
        preferences.edit().putBoolean("onboarding_complete", true).remove("onboarding_step").apply()
        onboardingStep = null
    }
}
